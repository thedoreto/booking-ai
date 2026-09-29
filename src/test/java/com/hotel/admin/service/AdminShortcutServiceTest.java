package com.hotel.admin.service;

import com.hotel.admin.service.AdminShortcutService.ActionChanges;
import com.hotel.admin.service.AdminShortcutService.InvalidShortcutException;
import com.hotel.admin.service.AdminShortcutService.ShortcutChanges;
import com.hotel.admin.service.AdminShortcutService.ShortcutIdTakenException;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.repository.ShortcutRepository;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelLanguages.Language;
import com.hotel.langchain.service.HotelLanguages.Languages;
import com.hotel.langchain.tools.ShortcutToolRunner;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminShortcutServiceTest {

    private static final String HOTEL = "40_robbers";
    private static final ObjectId PARKING = new ObjectId();
    private static final ObjectId BREAKFAST = new ObjectId();

    private final ShortcutRepository shortcutRepository = mock(ShortcutRepository.class);
    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final HotelLanguages hotelLanguages = mock(HotelLanguages.class);
    private final ShortcutToolRunner toolRunner = mock(ShortcutToolRunner.class);
    private final AdminShortcutService service =
            new AdminShortcutService(shortcutRepository, knowledgeService, hotelLanguages, toolRunner);

    @BeforeEach
    void hotel() {
        when(hotelLanguages.of(HOTEL)).thenReturn(new Languages(
                List.of(new Language("bg", "Български"), new Language("en", "English")), "bg"));
        when(toolRunner.hasTool("showMyBookings")).thenReturn(true);
        when(knowledgeService.findByIds(eq(HOTEL), anyList())).thenAnswer(invocation -> {
            List<ObjectId> ids = invocation.getArgument(1);
            return ids.stream().filter(id -> id.equals(PARKING) || id.equals(BREAKFAST)).map(id -> {
                KnowledgeDocument document = new KnowledgeDocument();
                document.setId(id.toHexString());
                return document;
            }).toList();
        });
        when(shortcutRepository.insert(eq(HOTEL), any(Shortcut.class))).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void newButtonGoesLastAndTheWholeOrderIsWritten() {
        when(shortcutRepository.findAll(HOTEL)).thenReturn(List.of(existing("parking"), existing("wifi")));

        Shortcut created = service.create(HOTEL, knowledgeButton(" breakfast ", BREAKFAST.toHexString(), PARKING.toHexString()));

        assertThat(created.getShortcutId()).isEqualTo("breakfast");
        assertThat(created.getOrder()).isEqualTo(3);
        assertThat(created.getLabel()).isEqualTo(Map.of("bg", "Закуска"));
        assertThat(created.getAction().getKnowledgeIds()).containsExactly(BREAKFAST, PARKING);
        assertThat(created.isActive()).isTrue();
        assertThat(created.isVisibleToGuest()).isTrue();
        verify(shortcutRepository).updateOrder(HOTEL, List.of("parking", "wifi", "breakfast"));
    }

    @Test
    void shortcutIdMustBeUnique() {
        when(shortcutRepository.findAll(HOTEL)).thenReturn(List.of(existing("breakfast")));

        assertThatThrownBy(() -> service.create(HOTEL, knowledgeButton("breakfast", BREAKFAST.toHexString())))
                .isInstanceOf(ShortcutIdTakenException.class);
        verify(shortcutRepository, never()).insert(anyString(), any());
    }

    @Test
    void shortcutIdIsRequiredAndSafe() {
        assertCode(() -> service.create(HOTEL, knowledgeButton(" ", BREAKFAST.toHexString())), "SHORTCUT_ID_REQUIRED");
        assertCode(() -> service.create(HOTEL, knowledgeButton("закуска", BREAKFAST.toHexString())), "SHORTCUT_ID_INVALID");
        assertCode(() -> service.create(HOTEL, knowledgeButton("a.b", BREAKFAST.toHexString())), "SHORTCUT_ID_INVALID");
        assertCode(() -> service.create(HOTEL, knowledgeButton("order", BREAKFAST.toHexString())), "SHORTCUT_ID_INVALID");
        assertCode(() -> service.create(HOTEL, knowledgeButton("x".repeat(51), BREAKFAST.toHexString())), "SHORTCUT_ID_INVALID");
    }

    @Test
    void labelInTheDefaultLanguageIsRequiredAndOtherLanguagesMustBeOfTheHotel() {
        assertCode(() -> service.update(HOTEL, "parking", button(Map.of("bg", " ", "en", "Parking"), tool("showMyBookings"))),
                "LABEL_REQUIRED");
        assertCode(() -> service.update(HOTEL, "parking", button(Map.of("bg", "Паркинг", "de", "Parken"), tool("showMyBookings"))),
                "UNKNOWN_LANGUAGE");
        verify(shortcutRepository, never()).update(anyString(), anyString(), any());
    }

    @Test
    void actionMustPointToExistingKnowledgeOrTool() {
        assertCode(() -> service.update(HOTEL, "parking", button(Map.of("bg", "Паркинг"), null)), "ACTION_REQUIRED");
        assertCode(() -> service.update(HOTEL, "parking", button(Map.of("bg", "Паркинг"), new ActionChanges("link", null, null))),
                "ACTION_REQUIRED");
        assertCode(() -> service.update(HOTEL, "parking", button(Map.of("bg", "Паркинг"), tool("dropDatabase"))), "UNKNOWN_TOOL");
        assertCode(() -> service.update(HOTEL, "parking", button(Map.of("bg", "Паркинг"), knowledge())), "KNOWLEDGE_REQUIRED");
        assertCode(() -> service.update(HOTEL, "parking", button(Map.of("bg", "Паркинг"), knowledge("not-an-id"))),
                "UNKNOWN_KNOWLEDGE");
        assertCode(() -> service.update(HOTEL, "parking",
                button(Map.of("bg", "Паркинг"), knowledge(PARKING.toHexString(), new ObjectId().toHexString()))), "UNKNOWN_KNOWLEDGE");
        verify(shortcutRepository, never()).update(anyString(), anyString(), any());
    }

    @Test
    void updateSavesCleanedButton() {
        when(shortcutRepository.update(eq(HOTEL), eq("parking"), any())).thenAnswer(invocation -> Optional.of(invocation.getArgument(2)));

        ShortcutChanges changes = new ShortcutChanges("ignored", Map.of("bg", " Моите резервации ", "en", ""), "  ",
                false, false, tool("showMyBookings"));
        Optional<Shortcut> updated = service.update(HOTEL, "parking", changes);

        ArgumentCaptor<Shortcut> saved = ArgumentCaptor.forClass(Shortcut.class);
        verify(shortcutRepository).update(eq(HOTEL), eq("parking"), saved.capture());
        assertThat(updated).isPresent();
        assertThat(saved.getValue().getLabel()).isEqualTo(Map.of("bg", "Моите резервации"));
        assertThat(saved.getValue().getCategory()).isNull();
        assertThat(saved.getValue().isActive()).isFalse();
        assertThat(saved.getValue().isVisibleToGuest()).isFalse();
        assertThat(saved.getValue().getAction().getTool()).isEqualTo("showMyBookings");
        assertThat(saved.getValue().getAction().getKnowledgeIds()).isNull();
    }

    @Test
    void reorderNeedsEveryButtonOnce() {
        when(shortcutRepository.findAll(HOTEL)).thenReturn(List.of(existing("parking"), existing("wifi")));

        assertCode(() -> service.reorder(HOTEL, List.of("wifi")), "INVALID_ORDER");
        assertCode(() -> service.reorder(HOTEL, List.of("wifi", "wifi")), "INVALID_ORDER");
        assertCode(() -> service.reorder(HOTEL, List.of("wifi", "breakfast")), "INVALID_ORDER");
        assertCode(() -> service.reorder(HOTEL, null), "INVALID_ORDER");
        verify(shortcutRepository, never()).updateOrder(anyString(), anyList());

        service.reorder(HOTEL, List.of("wifi", "parking"));
        verify(shortcutRepository).updateOrder(HOTEL, List.of("wifi", "parking"));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvalidShortcutException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private static ShortcutChanges knowledgeButton(String shortcutId, String... knowledgeIds) {
        return new ShortcutChanges(shortcutId, Map.of("bg", "Закуска"), null, null, null, knowledge(knowledgeIds));
    }

    private static ShortcutChanges button(Map<String, String> label, ActionChanges action) {
        return new ShortcutChanges(null, label, null, true, true, action);
    }

    private static ActionChanges knowledge(String... ids) {
        return new ActionChanges(Shortcut.Action.KNOWLEDGE, null, Arrays.asList(ids));
    }

    private static ActionChanges tool(String name) {
        return new ActionChanges(Shortcut.Action.TOOL, name, null);
    }

    private static Shortcut existing(String shortcutId) {
        Shortcut shortcut = new Shortcut();
        shortcut.setShortcutId(shortcutId);
        return shortcut;
    }
}
