package com.hotel.knowledge.service;

import com.hotel.knowledge.service.KnowledgeTranslator.TranslationFailedException;
import com.hotel.langchain.log.ChatLogService;
import com.hotel.langchain.model.GeminiUsage;
import com.hotel.langchain.service.HotelLanguages.Language;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeTranslatorTest {

    private static final List<Language> EN_DE = List.of(new Language("en", "English"), new Language("de", "Deutsch"));

    private final ChatLanguageModel model = mock(ChatLanguageModel.class);
    private final ChatLogService chatLogService = mock(ChatLogService.class);
    private final KnowledgeTranslator translator = new KnowledgeTranslator(model, "gemini-test", chatLogService);

    @Test
    void allLanguagesInOneCall() {
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("{\"en\": \" Parking is free. \", \"de\": \"Parken ist kostenlos.\"}"));

        assertThat(translator.translate("40_robbers", "Паркингът е безплатен.", "Български", EN_DE))
                .containsExactly(Map.entry("en", "Parking is free."), Map.entry("de", "Parken ist kostenlos."));

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        String prompt = ((UserMessage) request.getValue().messages().get(0)).singleText();
        assertThat(prompt).contains("от Български", "English (en), Deutsch (de)", "\"en\", \"de\"", "Паркингът е безплатен.");
    }

    @Test
    void jsonInsideCodeFencesIsAccepted() {
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("```json\n{\"en\": \"Parking is free.\"}\n```"));

        assertThat(translator.translate("40_robbers", "Текст", "Български", List.of(new Language("en", "English"))))
                .containsEntry("en", "Parking is free.");
    }

    @Test
    void geminiErrorNotJsonOrMissingLanguageFails() {
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("503"));
        assertThatThrownBy(() -> translator.translate("40_robbers", "Текст", "Български", EN_DE)).isInstanceOf(TranslationFailedException.class);

        reset(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("Here is the translation: Parking is free."));
        assertThatThrownBy(() -> translator.translate("40_robbers", "Текст", "Български", EN_DE)).isInstanceOf(TranslationFailedException.class);

        reset(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("{\"en\": \"Parking is free.\", \"de\": \"\"}"));
        assertThatThrownBy(() -> translator.translate("40_robbers", "Текст", "Български", EN_DE)).isInstanceOf(TranslationFailedException.class);
    }

    @Test
    void everyTranslationIsCountedWithItsTokensAndErrors() {
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("{\"en\": \"Parking is free.\"}"));
        translator.translate("40_robbers", "Текст", "Български", List.of(new Language("en", "English")));

        ArgumentCaptor<GeminiUsage> usage = ArgumentCaptor.forClass(GeminiUsage.class);
        verify(chatLogService).geminiUsage(eq("40_robbers"), usage.capture());
        assertThat(usage.getValue().getSource()).isEqualTo(GeminiUsage.ADMIN_TRANSLATION);
        assertThat(usage.getValue().getModel()).isEqualTo("gemini-test");
        assertThat(usage.getValue().getCalls()).isEqualTo(1);
        assertThat(usage.getValue().getErrors()).isZero();
        assertThat(usage.getValue().getInputTokens()).isEqualTo(120);
        assertThat(usage.getValue().getOutputTokens()).isEqualTo(15);

        reset(chatLogService);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("503"));
        assertThatThrownBy(() -> translator.translate("40_robbers", "Текст", "Български", EN_DE))
                .isInstanceOf(TranslationFailedException.class);
        verify(chatLogService).geminiUsage(eq("40_robbers"), usage.capture());
        assertThat(usage.getValue().getErrors()).isEqualTo(1);
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(120, 15)).build();
    }
}
