package com.hotel.admin.service;

import com.hotel.admin.service.AdminReportService.InvalidPeriodException;
import com.hotel.admin.service.SuggestionAnalyzer.ButtonRef;
import com.hotel.admin.service.SuggestionAnalyzer.KnowledgeRef;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.log.ChatReports.QuestionCount;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.model.SuggestionAnalysis;
import com.hotel.langchain.repository.ChatLogRepository;
import com.hotel.langchain.repository.ShortcutRepository;
import com.hotel.langchain.repository.SuggestionRepository;
import com.hotel.langchain.service.HotelLanguages;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Таб „Предложения“ в админ панела: анализ на въпросите от чата с Gemini (SuggestionAnalyzer) – само по бутон,
// най-много веднъж на COOLDOWN за хотел. Предложенията се записват в suggestions_<hotelId>; админът ги одобрява
// (създава знанието/бутона сам) или отхвърля. Нищо не влиза в знанията или бутоните без него.
@Service
public class AdminSuggestionService {

    public static final Duration COOLDOWN = Duration.ofHours(1);
    // Колко различни въпроса най-много отиват към Gemini (токените растат с тях)
    static final int MAX_UNANSWERED = 200;
    static final int MAX_ANSWERED = 300;
    // Колко от разгледаните предложения се пращат на Gemini, за да не ги предлага пак
    static final int MAX_HANDLED = 100;
    // Заглавие на знание без title – началото на текста
    private static final int TITLE_FROM_TEXT = 60;

    // Анализът е пускан преди по-малко от COOLDOWN – следващият е възможен от retryAt
    public static class TooSoonException extends RuntimeException {
        private final Instant retryAt;

        public TooSoonException(Instant retryAt) {
            super("TOO_SOON");
            this.retryAt = retryAt;
        }

        public Instant getRetryAt() { return retryAt; }
    }

    // Анализ на хотела вече върви (друг раздел/админ)
    public static class AnalysisRunningException extends RuntimeException {
        public AnalysisRunningException() {
            super("ANALYSIS_RUNNING");
        }
    }

    // В периода няма въпроси в чата – Gemini не се вика
    public static class NoQuestionsException extends RuntimeException {
        public NoQuestionsException() {
            super("NO_QUESTIONS");
        }
    }

    public static class InvalidStatusException extends RuntimeException {
        public InvalidStatusException() {
            super("INVALID_STATUS");
        }
    }

    private final ChatLogRepository chatLogRepository;
    private final SuggestionRepository suggestionRepository;
    private final KnowledgeService knowledgeService;
    private final ShortcutRepository shortcutRepository;
    private final HotelLanguages hotelLanguages;
    private final SuggestionAnalyzer analyzer;
    private final Clock clock;
    // Последният опит за хотел (и неуспешният харчи токени) – в паметта; след рестарт важи записаният анализ
    private final Map<String, Instant> lastAttempts = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public AdminSuggestionService(ChatLogRepository chatLogRepository, SuggestionRepository suggestionRepository,
                                  KnowledgeService knowledgeService, ShortcutRepository shortcutRepository,
                                  HotelLanguages hotelLanguages, SuggestionAnalyzer analyzer) {
        this(chatLogRepository, suggestionRepository, knowledgeService, shortcutRepository, hotelLanguages, analyzer,
                Clock.systemUTC());
    }

    AdminSuggestionService(ChatLogRepository chatLogRepository, SuggestionRepository suggestionRepository,
                           KnowledgeService knowledgeService, ShortcutRepository shortcutRepository,
                           HotelLanguages hotelLanguages, SuggestionAnalyzer analyzer, Clock clock) {
        this.chatLogRepository = chatLogRepository;
        this.suggestionRepository = suggestionRepository;
        this.knowledgeService = knowledgeService;
        this.shortcutRepository = shortcutRepository;
        this.hotelLanguages = hotelLanguages;
        this.analyzer = analyzer;
        this.clock = clock;
    }

    public Optional<SuggestionAnalysis> latest(String hotelId) {
        return suggestionRepository.findLatest(hotelId);
    }

    public SuggestionAnalysis analyze(String hotelId, int days) {
        if (days < 1 || days > AdminReportService.MAX_DAYS) {
            throw new InvalidPeriodException();
        }
        Instant now = clock.instant();
        Instant retryAt = lastAnalysis(hotelId).map(last -> last.plus(COOLDOWN)).orElse(null);
        if (retryAt != null && now.isBefore(retryAt)) {
            throw new TooSoonException(retryAt);
        }
        if (!running.add(hotelId)) {
            throw new AnalysisRunningException();
        }
        try {
            Instant from = now.minus(Duration.ofDays(days));
            List<QuestionCount> unanswered = chatLogRepository.questionCounts(hotelId, from, ChatLogEntry.NO_RESULT, MAX_UNANSWERED);
            List<QuestionCount> answered = chatLogRepository.questionCounts(hotelId, from, ChatLogEntry.OK, MAX_ANSWERED);
            if (unanswered.isEmpty() && answered.isEmpty()) {
                throw new NoQuestionsException();
            }
            lastAttempts.put(hotelId, now);
            List<KnowledgeDocument> knowledge = knowledgeService.findAll(hotelId);
            String defaultLanguage = hotelLanguages.of(hotelId).defaultLanguage();
            SuggestionAnalyzer.Result result = analyzer.analyze(hotelId, new SuggestionAnalyzer.Input(
                    hotelLanguages.nameOf(hotelId, defaultLanguage), unanswered, answered,
                    knowledge.stream().map(AdminSuggestionService::knowledgeRef).toList(),
                    buttons(hotelId, defaultLanguage), handled(hotelId)));

            SuggestionAnalysis analysis = new SuggestionAnalysis();
            analysis.setCreatedAt(now);
            analysis.setDays(days);
            analysis.setQuestions(unanswered.size() + answered.size());
            analysis.setInputTokens(result.inputTokens());
            analysis.setOutputTokens(result.outputTokens());
            Set<String> knowledgeIds = knowledge.stream().map(KnowledgeDocument::getId).collect(Collectors.toSet());
            analysis.setItems(result.items().stream()
                    .map(item -> checked(item, knowledgeIds))
                    .filter(Objects::nonNull)
                    .toList());
            SuggestionAnalysis saved = suggestionRepository.insert(hotelId, analysis);
            System.out.println("Suggestions analysed: hotelId=" + hotelId + ", days=" + days + ", questions="
                    + analysis.getQuestions() + ", items=" + analysis.getItems().size());
            return saved;
        } finally {
            running.remove(hotelId);
        }
    }

    // Одобрено (админът е създал знанието/бутона) или отхвърлено. false – няма такъв анализ или предложение.
    public boolean setStatus(String hotelId, String analysisId, String itemId, String status) {
        if (!SuggestionAnalysis.ACCEPTED.equals(status) && !SuggestionAnalysis.DISMISSED.equals(status)) {
            throw new InvalidStatusException();
        }
        return suggestionRepository.updateItemStatus(hotelId, analysisId, itemId, status);
    }

    // Кога е бил последният анализ: опитът в паметта или записаният (след рестарт)
    private Optional<Instant> lastAnalysis(String hotelId) {
        Instant attempt = lastAttempts.get(hotelId);
        Instant saved = suggestionRepository.findLatest(hotelId).map(SuggestionAnalysis::getCreatedAt).orElse(null);
        if (attempt == null || saved == null) {
            return Optional.ofNullable(attempt == null ? saved : attempt);
        }
        return Optional.of(attempt.isAfter(saved) ? attempt : saved);
    }

    // Предложение от Gemini, което може да се покаже: с тема и чернова / с надпис и поне едно съществуващо знание.
    // Непознатите id на знания (Gemini може да ги сгреши) се махат.
    private static SuggestionAnalysis.Item checked(SuggestionAnalysis.Item item, Set<String> knowledgeIds) {
        if (SuggestionAnalysis.MISSING_KNOWLEDGE.equals(item.getType())) {
            if (item.getTopic() == null || item.getDraft() == null) {
                return null;
            }
        } else if (SuggestionAnalysis.NEW_BUTTON.equals(item.getType())) {
            List<String> ids = item.getKnowledgeIds() == null ? List.of() : item.getKnowledgeIds().stream()
                    .filter(knowledgeIds::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
            if (item.getLabel() == null || ids.isEmpty()) {
                return null;
            }
            item.setKnowledgeIds(ids);
        } else {
            return null;
        }
        item.setId(UUID.randomUUID().toString());
        item.setStatus(SuggestionAnalysis.NEW);
        return item;
    }

    private static KnowledgeRef knowledgeRef(KnowledgeDocument d) {
        String title = d.getTitle();
        if (title == null || title.isBlank()) {
            String text = d.getText() == null ? "" : d.getText().replaceAll("\\s+", " ").trim();
            title = text.length() > TITLE_FROM_TEXT ? text.substring(0, TITLE_FROM_TEXT) + "…" : text;
        }
        return new KnowledgeRef(d.getId(), title, d.getCategory());
    }

    // Бутоните със знания (и неактивните) – надписът на езика по подразбиране на хотела
    private List<ButtonRef> buttons(String hotelId, String defaultLanguage) {
        List<ButtonRef> buttons = new ArrayList<>();
        for (Shortcut shortcut : shortcutRepository.findAll(hotelId)) {
            Shortcut.Action action = shortcut.getAction();
            if (action == null || action.getKnowledgeIds() == null || action.getKnowledgeIds().isEmpty()) {
                continue;
            }
            Map<String, String> labels = shortcut.getLabel() == null ? Map.of() : shortcut.getLabel();
            String label = labels.getOrDefault(defaultLanguage,
                    labels.values().stream().findFirst().orElse(shortcut.getShortcutId()));
            buttons.add(new ButtonRef(label, action.getKnowledgeIds().stream().map(ObjectId::toHexString).toList()));
        }
        return buttons;
    }

    // „Липсващо знание: Късно напускане (отхвърлено)“
    private List<String> handled(String hotelId) {
        return suggestionRepository.findHandled(hotelId, MAX_HANDLED).stream()
                .map(item -> (SuggestionAnalysis.NEW_BUTTON.equals(item.getType())
                        ? "Бутон: " + item.getLabel() : "Липсващо знание: " + item.getTopic())
                        + (SuggestionAnalysis.DISMISSED.equals(item.getStatus()) ? " (отхвърлено)" : " (създадено)"))
                .toList();
    }
}
