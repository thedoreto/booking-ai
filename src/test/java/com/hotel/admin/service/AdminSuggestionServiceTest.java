package com.hotel.admin.service;

import com.hotel.admin.service.AdminSuggestionService.InvalidStatusException;
import com.hotel.admin.service.AdminSuggestionService.NoQuestionsException;
import com.hotel.admin.service.AdminSuggestionService.TooSoonException;
import com.hotel.admin.service.SuggestionAnalyzer.AnalysisFailedException;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.log.ChatReports.QuestionCount;
import com.hotel.langchain.model.SuggestionAnalysis;
import com.hotel.langchain.repository.ChatLogRepository;
import com.hotel.langchain.repository.ShortcutRepository;
import com.hotel.langchain.repository.SuggestionRepository;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelLanguages.Language;
import com.hotel.langchain.service.HotelLanguages.Languages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSuggestionServiceTest {

    private static final String HOTEL = "40_robbers";
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z"); // MutableClock започва оттам

    private final ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
    private final SuggestionRepository suggestionRepository = mock(SuggestionRepository.class);
    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final ShortcutRepository shortcutRepository = mock(ShortcutRepository.class);
    private final HotelLanguages hotelLanguages = mock(HotelLanguages.class);
    private final SuggestionAnalyzer analyzer = mock(SuggestionAnalyzer.class);
    private final MutableClock clock = new MutableClock();
    private final AdminSuggestionService service = new AdminSuggestionService(chatLogRepository, suggestionRepository,
            knowledgeService, shortcutRepository, hotelLanguages, analyzer, clock);

    @BeforeEach
    void hotel() {
        when(hotelLanguages.of(HOTEL)).thenReturn(new Languages(List.of(new Language("bg", "Български")), "bg"));
        when(hotelLanguages.nameOf(HOTEL, "bg")).thenReturn("Български");
        when(chatLogRepository.questionCounts(eq(HOTEL), any(), eq(ChatLogEntry.NO_RESULT), anyInt()))
                .thenReturn(List.of(new QuestionCount("Имате ли басейн?", 4)));
        when(knowledgeService.findAll(HOTEL)).thenReturn(List.of(knowledge("k1", "Паркинг")));
        when(suggestionRepository.findLatest(HOTEL)).thenReturn(Optional.empty());
        when(suggestionRepository.insert(eq(HOTEL), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void suggestionsFromGeminiAreCheckedAndSaved() {
        when(analyzer.analyze(eq(HOTEL), any())).thenReturn(new SuggestionAnalyzer.Result(List.of(
                missing("Басейн", "Басейнът работи до [час]."),
                missing("Без чернова", null),
                button("Паркинг", "k1", "k-unknown", "k1"),
                button("Несъществуващо", "k-unknown")), 900, 120));

        SuggestionAnalysis analysis = service.analyze(HOTEL, 30);

        assertThat(analysis.getCreatedAt()).isEqualTo(NOW);
        assertThat(analysis.getDays()).isEqualTo(30);
        assertThat(analysis.getQuestions()).isEqualTo(1);
        assertThat(analysis.getInputTokens()).isEqualTo(900);
        assertThat(analysis.getItems()).extracting(SuggestionAnalysis.Item::getType)
                .containsExactly(SuggestionAnalysis.MISSING_KNOWLEDGE, SuggestionAnalysis.NEW_BUTTON);
        assertThat(analysis.getItems()).allSatisfy(item -> {
            assertThat(item.getId()).isNotBlank();
            assertThat(item.getStatus()).isEqualTo(SuggestionAnalysis.NEW);
        });
        // Непознатото знание е махнато, повтореното – веднъж
        assertThat(analysis.getItems().get(1).getKnowledgeIds()).containsExactly("k1");

        ArgumentCaptor<SuggestionAnalyzer.Input> input = ArgumentCaptor.forClass(SuggestionAnalyzer.Input.class);
        verify(analyzer).analyze(eq(HOTEL), input.capture());
        assertThat(input.getValue().languageName()).isEqualTo("Български");
        assertThat(input.getValue().unanswered()).containsExactly(new QuestionCount("Имате ли басейн?", 4));
        assertThat(input.getValue().knowledge()).extracting(SuggestionAnalyzer.KnowledgeRef::title).containsExactly("Паркинг");
        verify(chatLogRepository).questionCounts(HOTEL, NOW.minus(Duration.ofDays(30)), ChatLogEntry.NO_RESULT, 200);
        verify(chatLogRepository).questionCounts(HOTEL, NOW.minus(Duration.ofDays(30)), ChatLogEntry.OK, 300);
    }

    @Test
    void afterAFailureTheNextTryIsInFiveMinutes() {
        when(analyzer.analyze(eq(HOTEL), any()))
                .thenThrow(new AnalysisFailedException("503", null))
                .thenReturn(new SuggestionAnalyzer.Result(List.of(), 1, 1));
        assertThatThrownBy(() -> service.analyze(HOTEL, 30)).isInstanceOf(AnalysisFailedException.class);

        clock.advance(Duration.ofMinutes(4));
        assertThatThrownBy(() -> service.analyze(HOTEL, 30)).isInstanceOfSatisfying(TooSoonException.class,
                e -> assertThat(e.getRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5))));

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.analyze(HOTEL, 30).getItems()).isEmpty();
    }

    @Test
    void afterASavedAnalysisTheNextIsInAnHourAlsoAfterARestart() {
        // Записаният анализ важи и за нова инстанция (след рестарт)
        SuggestionAnalysis saved = new SuggestionAnalysis();
        saved.setCreatedAt(clock.instant().minus(Duration.ofMinutes(10)));
        when(suggestionRepository.findLatest(HOTEL)).thenReturn(Optional.of(saved));
        AdminSuggestionService restarted = new AdminSuggestionService(chatLogRepository, suggestionRepository,
                knowledgeService, shortcutRepository, hotelLanguages, analyzer, clock);
        assertThatThrownBy(() -> restarted.analyze(HOTEL, 30)).isInstanceOf(TooSoonException.class);
    }

    @Test
    void withoutQuestionsGeminiIsNotCalledAndTheHourDoesNotStart() {
        when(chatLogRepository.questionCounts(eq(HOTEL), any(), anyString(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.analyze(HOTEL, 30)).isInstanceOf(NoQuestionsException.class);
        assertThatThrownBy(() -> service.analyze(HOTEL, 30)).isInstanceOf(NoQuestionsException.class);
        verify(analyzer, never()).analyze(anyString(), any());
    }

    @Test
    void statusIsAcceptedOrDismissed() {
        when(suggestionRepository.updateItemStatus(HOTEL, "a1", "i1", "dismissed")).thenReturn(true);

        assertThat(service.setStatus(HOTEL, "a1", "i1", "dismissed")).isTrue();
        assertThat(service.setStatus(HOTEL, "a1", "unknown", "accepted")).isFalse();
        assertThatThrownBy(() -> service.setStatus(HOTEL, "a1", "i1", "new")).isInstanceOf(InvalidStatusException.class);
    }

    private static KnowledgeDocument knowledge(String id, String title) {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(id);
        d.setTitle(title);
        return d;
    }

    private static SuggestionAnalysis.Item missing(String topic, String draft) {
        SuggestionAnalysis.Item item = new SuggestionAnalysis.Item();
        item.setType(SuggestionAnalysis.MISSING_KNOWLEDGE);
        item.setTopic(topic);
        item.setDraft(draft);
        return item;
    }

    private static SuggestionAnalysis.Item button(String label, String... knowledgeIds) {
        SuggestionAnalysis.Item item = new SuggestionAnalysis.Item();
        item.setType(SuggestionAnalysis.NEW_BUTTON);
        item.setLabel(label);
        item.setKnowledgeIds(new ArrayList<>(List.of(knowledgeIds)));
        return item;
    }
}
