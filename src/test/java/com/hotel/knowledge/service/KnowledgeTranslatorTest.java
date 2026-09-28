package com.hotel.knowledge.service;

import com.hotel.knowledge.service.KnowledgeTranslator.TranslationFailedException;
import com.hotel.langchain.service.HotelLanguages.Language;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeTranslatorTest {

    private static final List<Language> EN_DE = List.of(new Language("en", "English"), new Language("de", "Deutsch"));

    private final ChatLanguageModel model = mock(ChatLanguageModel.class);
    private final KnowledgeTranslator translator = new KnowledgeTranslator(model);

    @Test
    void allLanguagesInOneCall() {
        when(model.chat(anyString())).thenReturn("{\"en\": \" Parking is free. \", \"de\": \"Parken ist kostenlos.\"}");

        assertThat(translator.translate("Паркингът е безплатен.", "Български", EN_DE))
                .containsExactly(Map.entry("en", "Parking is free."), Map.entry("de", "Parken ist kostenlos."));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(prompt.capture());
        assertThat(prompt.getValue()).contains("от Български", "English (en), Deutsch (de)", "\"en\", \"de\"", "Паркингът е безплатен.");
    }

    @Test
    void jsonInsideCodeFencesIsAccepted() {
        when(model.chat(anyString())).thenReturn("```json\n{\"en\": \"Parking is free.\"}\n```");

        assertThat(translator.translate("Текст", "Български", List.of(new Language("en", "English"))))
                .containsEntry("en", "Parking is free.");
    }

    @Test
    void geminiErrorNotJsonOrMissingLanguageFails() {
        when(model.chat(anyString())).thenThrow(new RuntimeException("503"));
        assertThatThrownBy(() -> translator.translate("Текст", "Български", EN_DE)).isInstanceOf(TranslationFailedException.class);

        reset(model);
        when(model.chat(anyString())).thenReturn("Here is the translation: Parking is free.");
        assertThatThrownBy(() -> translator.translate("Текст", "Български", EN_DE)).isInstanceOf(TranslationFailedException.class);

        reset(model);
        when(model.chat(anyString())).thenReturn("{\"en\": \"Parking is free.\", \"de\": \"\"}");
        assertThatThrownBy(() -> translator.translate("Текст", "Български", EN_DE)).isInstanceOf(TranslationFailedException.class);
    }
}
