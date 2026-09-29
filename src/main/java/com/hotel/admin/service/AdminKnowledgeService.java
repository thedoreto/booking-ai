package com.hotel.admin.service;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.knowledge.service.KnowledgeService.InvalidKnowledgeException;
import com.hotel.knowledge.service.KnowledgeService.KnowledgeChanges;
import com.hotel.knowledge.service.KnowledgeTranslator;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.repository.ShortcutRepository;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelLanguages.Language;
import com.hotel.langchain.service.HotelLanguages.Languages;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// Знанията в админ панела заедно с бутоните, които ги ползват (action.knowledgeIds в shortcuts_<hotelId>,
// и неактивните). Знание, което се ползва от бутон, не се трие – първо се сменя бутонът.
// Преводите (за бутоните): на езиците на хотела без езика по подразбиране – text е на него. Gemini превежда само
// по бутон в админа: предложение за един език (без запис) или всички езици наведнъж (със запис).
@Service
public class AdminKnowledgeService {

    // Бутон, който ползва знанието: shortcutId и етикетът на езика по подразбиране на хотела
    public record ShortcutRef(String shortcutId, String label) {}

    public record KnowledgeWithUsage(KnowledgeDocument document, List<ShortcutRef> usedBy) {}

    // Знанието се ползва от бутоните usedBy – не е изтрито
    public static class KnowledgeInUseException extends RuntimeException {
        private final List<ShortcutRef> usedBy;

        public KnowledgeInUseException(List<ShortcutRef> usedBy) {
            super("IN_USE");
            this.usedBy = usedBy;
        }

        public List<ShortcutRef> getUsedBy() { return usedBy; }
    }

    private final KnowledgeService knowledgeService;
    private final ShortcutRepository shortcutRepository;
    private final HotelLanguages hotelLanguages;
    private final KnowledgeTranslator translator;

    public AdminKnowledgeService(KnowledgeService knowledgeService, ShortcutRepository shortcutRepository,
                                 HotelLanguages hotelLanguages, KnowledgeTranslator translator) {
        this.knowledgeService = knowledgeService;
        this.shortcutRepository = shortcutRepository;
        this.hotelLanguages = hotelLanguages;
        this.translator = translator;
    }

    // Предложение за превод на един език – Gemini, без запис. Празно – няма такова знание.
    public Optional<String> suggestTranslation(String hotelId, String id, String language) {
        Language target = translationLanguage(hotelId, language);
        return knowledgeService.findById(hotelId, id)
                .map(d -> translator.translate(hotelId, d.getText(), defaultLanguageName(hotelId), List.of(target)).get(target.code()));
    }

    // Записва превода на един език (от админа – ръчно или поправено предложение); другите езици не се пипат
    public Optional<KnowledgeWithUsage> saveTranslation(String hotelId, String id, String language, String text) {
        translationLanguage(hotelId, language);
        return knowledgeService.setTranslations(hotelId, id, Map.of(language, text == null ? "" : text))
                .map(d -> withUsage(d, usage(hotelId)));
    }

    // Превежда основния текст на всички езици на хотела и ги записва (ръчните поправки се заменят)
    public Optional<KnowledgeWithUsage> translateAll(String hotelId, String id) {
        List<Language> targets = translationLanguages(hotelId);
        if (targets.isEmpty()) {
            throw new InvalidKnowledgeException("NO_LANGUAGES");
        }
        return knowledgeService.findById(hotelId, id)
                .flatMap(d -> knowledgeService.setTranslations(hotelId, id,
                        translator.translate(hotelId, d.getText(), defaultLanguageName(hotelId), targets)))
                .map(d -> withUsage(d, usage(hotelId)));
    }

    // Езиците, на които се превежда: на хотела, без езика по подразбиране
    private List<Language> translationLanguages(String hotelId) {
        Languages languages = hotelLanguages.of(hotelId);
        return languages.languages().stream()
                .filter(l -> !l.code().equals(languages.defaultLanguage()))
                .toList();
    }

    private Language translationLanguage(String hotelId, String code) {
        return translationLanguages(hotelId).stream()
                .filter(l -> l.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new InvalidKnowledgeException("UNKNOWN_LANGUAGE"));
    }

    private String defaultLanguageName(String hotelId) {
        return hotelLanguages.nameOf(hotelId, hotelLanguages.of(hotelId).defaultLanguage());
    }

    public List<KnowledgeWithUsage> list(String hotelId) {
        Map<String, List<ShortcutRef>> usage = usage(hotelId);
        return knowledgeService.findAll(hotelId).stream()
                .map(d -> withUsage(d, usage))
                .toList();
    }

    public KnowledgeWithUsage create(String hotelId, KnowledgeChanges changes) {
        // Ново знание още не се ползва от бутон
        return new KnowledgeWithUsage(knowledgeService.create(hotelId, changes), List.of());
    }

    public Optional<KnowledgeWithUsage> update(String hotelId, String id, KnowledgeChanges changes) {
        return knowledgeService.update(hotelId, id, changes).map(d -> withUsage(d, usage(hotelId)));
    }

    // false – няма такова знание; KnowledgeInUseException – ползва се от бутон, нищо не е изтрито
    public boolean delete(String hotelId, String id) {
        List<ShortcutRef> usedBy = usage(hotelId).getOrDefault(id, List.of());
        if (!usedBy.isEmpty()) {
            throw new KnowledgeInUseException(usedBy);
        }
        return knowledgeService.delete(hotelId, id);
    }

    private static KnowledgeWithUsage withUsage(KnowledgeDocument document, Map<String, List<ShortcutRef>> usage) {
        return new KnowledgeWithUsage(document, usage.getOrDefault(document.getId(), List.of()));
    }

    // id на знание (hex) → бутоните, които го ползват
    private Map<String, List<ShortcutRef>> usage(String hotelId) {
        String language = hotelLanguages.of(hotelId).defaultLanguage();
        Map<String, List<ShortcutRef>> usage = new HashMap<>();
        for (Shortcut shortcut : shortcutRepository.findKnowledgeShortcuts(hotelId)) {
            List<ObjectId> ids = shortcut.getAction() != null ? shortcut.getAction().getKnowledgeIds() : null;
            if (ids == null) {
                continue;
            }
            String label = shortcut.labelIn(language, language);
            ShortcutRef ref = new ShortcutRef(shortcut.getShortcutId(), label != null ? label : shortcut.getShortcutId());
            ids.stream()
                    .filter(Objects::nonNull)
                    .map(ObjectId::toHexString)
                    .distinct()
                    .forEach(id -> usage.computeIfAbsent(id, k -> new ArrayList<>()).add(ref));
        }
        return usage;
    }
}
