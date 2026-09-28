package com.hotel.admin.service;

import com.hotel.admin.service.AdminKnowledgeService.KnowledgeInUseException;
import com.hotel.admin.service.AdminKnowledgeService.KnowledgeWithUsage;
import com.hotel.admin.service.AdminKnowledgeService.ShortcutRef;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.knowledge.service.KnowledgeService.InvalidKnowledgeException;
import com.hotel.knowledge.service.KnowledgeTranslator;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.repository.ShortcutRepository;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelLanguages.Language;
import com.hotel.langchain.service.HotelLanguages.Languages;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminKnowledgeServiceTest {

    private static final ObjectId PARKING = new ObjectId();
    private static final ObjectId BREAKFAST = new ObjectId();
    private static final ObjectId UNUSED = new ObjectId();

    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final ShortcutRepository shortcutRepository = mock(ShortcutRepository.class);
    private final HotelLanguages hotelLanguages = mock(HotelLanguages.class);
    private final KnowledgeTranslator translator = mock(KnowledgeTranslator.class);
    private final AdminKnowledgeService service =
            new AdminKnowledgeService(knowledgeService, shortcutRepository, hotelLanguages, translator);

    private static final Language BG = new Language("bg", "Български");
    private static final Language EN = new Language("en", "English");
    private static final Language DE = new Language("de", "Deutsch");

    @BeforeEach
    void buttons() {
        when(hotelLanguages.of("40_robbers")).thenReturn(new Languages(List.of(BG, EN, DE), "en"));
        when(hotelLanguages.nameOf("40_robbers", "en")).thenReturn("English");
        Shortcut inactive = shortcut("info", Map.of("bg", "Информация", "en", "Info"), PARKING, BREAKFAST);
        inactive.setIsActive(false);
        when(shortcutRepository.findKnowledgeShortcuts("40_robbers")).thenReturn(List.of(
                shortcut("parking", Map.of("bg", "Паркинг", "en", "Parking"), PARKING, PARKING),
                inactive,
                shortcut("no_label", null, BREAKFAST),
                shortcut("broken", Map.of("bg", "Счупен"), (ObjectId) null)));
    }

    @Test
    void everyDocumentSaysWhichButtonsUseItIncludingInactiveOnes() {
        when(knowledgeService.findAll("40_robbers")).thenReturn(List.of(doc(PARKING), doc(BREAKFAST), doc(UNUSED)));

        List<KnowledgeWithUsage> list = service.list("40_robbers");

        // Етикетът – на езика по подразбиране на хотела; без етикет – shortcutId; един бутон – веднъж
        assertThat(list.get(0).usedBy()).containsExactly(new ShortcutRef("parking", "Parking"), new ShortcutRef("info", "Info"));
        assertThat(list.get(1).usedBy()).containsExactly(new ShortcutRef("info", "Info"), new ShortcutRef("no_label", "no_label"));
        assertThat(list.get(2).usedBy()).isEmpty();
    }

    @Test
    void usedKnowledgeIsNotDeleted() {
        assertThatThrownBy(() -> service.delete("40_robbers", PARKING.toHexString()))
                .isInstanceOfSatisfying(KnowledgeInUseException.class, e -> assertThat(e.getUsedBy())
                        .extracting(ShortcutRef::shortcutId).containsExactly("parking", "info"));
        verify(knowledgeService, never()).delete(anyString(), anyString());
    }

    @Test
    void unusedKnowledgeIsDeleted() {
        when(knowledgeService.delete("40_robbers", UNUSED.toHexString())).thenReturn(true);

        assertThat(service.delete("40_robbers", UNUSED.toHexString())).isTrue();
    }

    @Test
    void newKnowledgeIsNotUsedYet() {
        KnowledgeService.KnowledgeChanges changes = new KnowledgeService.KnowledgeChanges(null, null, null, null, "Текст");
        when(knowledgeService.create("40_robbers", changes)).thenReturn(doc(new ObjectId()));

        assertThat(service.create("40_robbers", changes).usedBy()).isEmpty();
    }

    @Test
    void suggestionIsForOneLanguageAndIsNotSaved() {
        when(knowledgeService.findById("40_robbers", PARKING.toHexString())).thenReturn(Optional.of(doc(PARKING)));
        when(translator.translate("Текст", "English", List.of(DE))).thenReturn(Map.of("de", "Text auf Deutsch"));

        assertThat(service.suggestTranslation("40_robbers", PARKING.toHexString(), "de")).contains("Text auf Deutsch");
        verify(knowledgeService, never()).setTranslations(anyString(), anyString(), anyMap());
    }

    @Test
    void onlyTheHotelsLanguagesWithoutTheDefaultOneCanBeTranslated() {
        for (String language : List.of("en", "fr", "")) {
            assertThatThrownBy(() -> service.saveTranslation("40_robbers", PARKING.toHexString(), language, "Text"))
                    .isInstanceOfSatisfying(InvalidKnowledgeException.class, e -> assertThat(e.getCode()).isEqualTo("UNKNOWN_LANGUAGE"));
            assertThatThrownBy(() -> service.suggestTranslation("40_robbers", PARKING.toHexString(), language))
                    .isInstanceOf(InvalidKnowledgeException.class);
        }
        verify(translator, never()).translate(anyString(), anyString(), any());
        verify(knowledgeService, never()).setTranslations(anyString(), anyString(), anyMap());
    }

    @Test
    void savingOneLanguageSendsOnlyThatLanguage() {
        when(knowledgeService.setTranslations("40_robbers", PARKING.toHexString(), Map.of("bg", "Паркинг")))
                .thenReturn(Optional.of(doc(PARKING)));

        assertThat(service.saveTranslation("40_robbers", PARKING.toHexString(), "bg", "Паркинг")).isPresent();
    }

    @Test
    void translateAllUsesEveryLanguageExceptTheDefaultOneAndSavesThem() {
        when(knowledgeService.findById("40_robbers", PARKING.toHexString())).thenReturn(Optional.of(doc(PARKING)));
        Map<String, String> translations = Map.of("bg", "Текст", "de", "Text");
        when(translator.translate("Текст", "English", List.of(BG, DE))).thenReturn(translations);
        when(knowledgeService.setTranslations("40_robbers", PARKING.toHexString(), translations)).thenReturn(Optional.of(doc(PARKING)));

        assertThat(service.translateAll("40_robbers", PARKING.toHexString())).isPresent();
        verify(knowledgeService).setTranslations("40_robbers", PARKING.toHexString(), translations);
    }

    @Test
    void translateAllWithoutOtherLanguagesOrDocument() {
        when(hotelLanguages.of("one_language")).thenReturn(new Languages(List.of(BG), "bg"));
        assertThatThrownBy(() -> service.translateAll("one_language", PARKING.toHexString()))
                .isInstanceOfSatisfying(InvalidKnowledgeException.class, e -> assertThat(e.getCode()).isEqualTo("NO_LANGUAGES"));

        when(knowledgeService.findById(eq("40_robbers"), anyString())).thenReturn(Optional.empty());
        assertThat(service.translateAll("40_robbers", UNUSED.toHexString())).isEmpty();
        verify(translator, never()).translate(anyString(), anyString(), any());
    }

    private static Shortcut shortcut(String shortcutId, Map<String, String> label, ObjectId... knowledgeIds) {
        Shortcut.Action action = new Shortcut.Action();
        action.setType(Shortcut.Action.KNOWLEDGE);
        action.setKnowledgeIds(Arrays.asList(knowledgeIds));
        Shortcut shortcut = new Shortcut();
        shortcut.setShortcutId(shortcutId);
        shortcut.setLabel(label);
        shortcut.setAction(action);
        return shortcut;
    }

    private static KnowledgeDocument doc(ObjectId id) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id.toHexString());
        doc.setText("Текст");
        return doc;
    }
}
