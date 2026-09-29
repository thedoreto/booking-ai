package com.hotel.admin.service;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.repository.ShortcutRepository;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelLanguages.Language;
import com.hotel.langchain.service.HotelLanguages.Languages;
import com.hotel.langchain.tools.ShortcutToolRunner;
import com.hotel.langchain.tools.ShortcutToolRunner.ToolInfo;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Бутоните на хотела в админ панела (shortcuts_<hotelId>, и неактивните): преглед, добавяне, редакция, изтриване, ред.
// shortcutId е задължителен, уникален в хотела и не се сменя. Етикетът на езика по подразбиране е задължителен,
// другите – само на езиците на хотела. Действието: знания, които съществуват (в избрания ред), или tool от HotelTools.
@Service
public class AdminShortcutService {

    public static final int MAX_SHORTCUT_ID_LENGTH = 50;
    private static final Pattern SHORTCUT_ID = Pattern.compile("[A-Za-z0-9_-]+");
    // Адресите /api/admin/shortcuts/order и /tools – бутон с това име не би могъл да се редактира
    private static final Set<String> RESERVED_IDS = Set.of("order", "tools");

    // Данните от админа; shortcutId – само при ново (при редакция е от адреса)
    public record ShortcutChanges(String shortcutId, Map<String, String> label, String category, Boolean isActive,
                                  Boolean guestVisible, ActionChanges action) {}

    // type: knowledge (knowledgeIds – hex _id от knowledge_<hotelId>) или tool (tool – името)
    public record ActionChanges(String type, String tool, List<String> knowledgeIds) {}

    // Грешни данни; code – за админ панела (SHORTCUT_ID_REQUIRED, LABEL_REQUIRED, UNKNOWN_TOOL...)
    public static class InvalidShortcutException extends RuntimeException {
        private final String code;

        public InvalidShortcutException(String code) {
            super(code);
            this.code = code;
        }

        public String getCode() { return code; }
    }

    public static class ShortcutIdTakenException extends RuntimeException {
        public ShortcutIdTakenException(String shortcutId) {
            super("SHORTCUT_ID_TAKEN: " + shortcutId);
        }
    }

    private final ShortcutRepository shortcutRepository;
    private final KnowledgeService knowledgeService;
    private final HotelLanguages hotelLanguages;
    private final ShortcutToolRunner shortcutToolRunner;

    public AdminShortcutService(ShortcutRepository shortcutRepository, KnowledgeService knowledgeService,
                                HotelLanguages hotelLanguages, ShortcutToolRunner shortcutToolRunner) {
        this.shortcutRepository = shortcutRepository;
        this.knowledgeService = knowledgeService;
        this.hotelLanguages = hotelLanguages;
        this.shortcutToolRunner = shortcutToolRunner;
    }

    public List<Shortcut> list(String hotelId) {
        return shortcutRepository.findAll(hotelId);
    }

    public List<ToolInfo> tools() {
        return shortcutToolRunner.tools();
    }

    // Новият бутон отива последен; целият ред се записва наново (1..n), и за старите бутони без order
    public Shortcut create(String hotelId, ShortcutChanges changes) {
        String shortcutId = validShortcutId(changes.shortcutId());
        Shortcut shortcut = toShortcut(hotelId, changes);
        shortcut.setShortcutId(shortcutId);
        List<Shortcut> existing = shortcutRepository.findAll(hotelId);
        if (existing.stream().anyMatch(s -> shortcutId.equals(s.getShortcutId()))) {
            throw new ShortcutIdTakenException(shortcutId);
        }
        shortcut.setOrder(existing.size() + 1);
        Shortcut created = shortcutRepository.insert(hotelId, shortcut);
        List<String> order = new ArrayList<>(existing.stream().map(Shortcut::getShortcutId).toList());
        order.add(shortcutId);
        shortcutRepository.updateOrder(hotelId, order);
        System.out.println("Shortcut created: hotelId=" + hotelId + ", shortcutId=" + shortcutId);
        return created;
    }

    // Празно – няма такъв бутон
    public Optional<Shortcut> update(String hotelId, String shortcutId, ShortcutChanges changes) {
        Shortcut shortcut = toShortcut(hotelId, changes);
        Optional<Shortcut> updated = shortcutRepository.update(hotelId, shortcutId, shortcut);
        updated.ifPresent(s -> System.out.println("Shortcut updated: hotelId=" + hotelId + ", shortcutId=" + shortcutId));
        return updated;
    }

    // false – няма такъв бутон
    public boolean delete(String hotelId, String shortcutId) {
        boolean deleted = shortcutRepository.delete(hotelId, shortcutId);
        if (deleted) {
            System.out.println("Shortcut deleted: hotelId=" + hotelId + ", shortcutId=" + shortcutId);
        }
        return deleted;
    }

    // Новият ред в чата – всички бутони на хотела, всеки веднъж (иначе INVALID_ORDER, нищо не е записано)
    public List<Shortcut> reorder(String hotelId, List<String> shortcutIds) {
        Set<String> existing = shortcutRepository.findAll(hotelId).stream()
                .map(Shortcut::getShortcutId)
                .collect(Collectors.toSet());
        if (shortcutIds == null || shortcutIds.size() != existing.size() || !existing.equals(new HashSet<>(shortcutIds))) {
            throw new InvalidShortcutException("INVALID_ORDER");
        }
        shortcutRepository.updateOrder(hotelId, shortcutIds);
        return shortcutRepository.findAll(hotelId);
    }

    private static String validShortcutId(String shortcutId) {
        String id = shortcutId == null ? "" : shortcutId.trim();
        if (id.isEmpty()) {
            throw new InvalidShortcutException("SHORTCUT_ID_REQUIRED");
        }
        if (id.length() > MAX_SHORTCUT_ID_LENGTH || !SHORTCUT_ID.matcher(id).matches() || RESERVED_IDS.contains(id)) {
            throw new InvalidShortcutException("SHORTCUT_ID_INVALID");
        }
        return id;
    }

    // Бутонът без shortcutId и order – проверени етикет и действие
    private Shortcut toShortcut(String hotelId, ShortcutChanges changes) {
        Shortcut shortcut = new Shortcut();
        shortcut.setLabel(validLabel(hotelId, changes.label()));
        shortcut.setCategory(changes.category() == null || changes.category().isBlank() ? null : changes.category().trim());
        shortcut.setIsActive(changes.isActive() == null || changes.isActive());
        if (Boolean.FALSE.equals(changes.guestVisible())) {
            Shortcut.Guest guest = new Shortcut.Guest();
            guest.setIsActive(false);
            shortcut.setGuest(guest);
        }
        shortcut.setAction(validAction(hotelId, changes.action()));
        return shortcut;
    }

    // Празните езици се махат; езикът по подразбиране е задължителен, другите – само от езиците на хотела
    private Map<String, String> validLabel(String hotelId, Map<String, String> label) {
        Languages languages = hotelLanguages.of(hotelId);
        Set<String> codes = languages.languages().stream().map(Language::code).collect(Collectors.toSet());
        Map<String, String> clean = new LinkedHashMap<>();
        if (label != null) {
            label.forEach((language, text) -> {
                if (text != null && !text.isBlank()) {
                    if (!codes.contains(language)) {
                        throw new InvalidShortcutException("UNKNOWN_LANGUAGE");
                    }
                    clean.put(language, text.trim());
                }
            });
        }
        if (!clean.containsKey(languages.defaultLanguage())) {
            throw new InvalidShortcutException("LABEL_REQUIRED");
        }
        return clean;
    }

    private Shortcut.Action validAction(String hotelId, ActionChanges changes) {
        if (changes == null || changes.type() == null) {
            throw new InvalidShortcutException("ACTION_REQUIRED");
        }
        Shortcut.Action action = new Shortcut.Action();
        action.setType(changes.type());
        switch (changes.type()) {
            case Shortcut.Action.TOOL -> {
                if (!shortcutToolRunner.hasTool(changes.tool())) {
                    throw new InvalidShortcutException("UNKNOWN_TOOL");
                }
                action.setTool(changes.tool());
            }
            case Shortcut.Action.KNOWLEDGE -> action.setKnowledgeIds(validKnowledgeIds(hotelId, changes.knowledgeIds()));
            default -> throw new InvalidShortcutException("ACTION_REQUIRED");
        }
        return action;
    }

    // Поне едно знание; всички трябва да съществуват в knowledge_<hotelId>; повторенията се махат, редът остава
    private List<ObjectId> validKnowledgeIds(String hotelId, List<String> knowledgeIds) {
        List<String> ids = knowledgeIds == null ? List.of() : knowledgeIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            throw new InvalidShortcutException("KNOWLEDGE_REQUIRED");
        }
        if (!ids.stream().allMatch(id -> id != null && ObjectId.isValid(id))) {
            throw new InvalidShortcutException("UNKNOWN_KNOWLEDGE");
        }
        List<ObjectId> objectIds = ids.stream().map(ObjectId::new).toList();
        List<KnowledgeDocument> found = knowledgeService.findByIds(hotelId, objectIds);
        if (found.size() != objectIds.size()) {
            throw new InvalidShortcutException("UNKNOWN_KNOWLEDGE");
        }
        return objectIds;
    }
}
