package com.hotel.langchain.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryingChatLanguageModelTest {

    private static final RuntimeException OVERLOADED =
            new RuntimeException("HTTP error (503): { \"error\": { \"status\": \"UNAVAILABLE\" } }");

    private final ChatLanguageModel gemini = mock(ChatLanguageModel.class);
    // Без паузи – тестът не чака
    private final RetryingChatLanguageModel model = new RetryingChatLanguageModel(gemini, 0, 0);
    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Здравей")).build();
    private final ChatResponse answer = ChatResponse.builder().aiMessage(AiMessage.from("Здравейте")).build();

    @Test
    void overloadedModelIsTriedAgain() {
        when(gemini.chat(any(ChatRequest.class))).thenThrow(OVERLOADED).thenThrow(OVERLOADED).thenReturn(answer);

        assertThat(model.chat(request)).isSameAs(answer);
        verify(gemini, times(3)).chat(request);
    }

    @Test
    void afterTheLastRetryTheErrorGoesOn() {
        when(gemini.chat(any(ChatRequest.class))).thenThrow(OVERLOADED);

        assertThatThrownBy(() -> model.chat(request)).isSameAs(OVERLOADED);
        // Първият опит + 2 повторения
        verify(gemini, times(3)).chat(request);
    }

    @Test
    void otherErrorsAreNotRetried() {
        RuntimeException quota = new RuntimeException("HTTP error (429): RESOURCE_EXHAUSTED");
        when(gemini.chat(any(ChatRequest.class))).thenThrow(quota);

        assertThatThrownBy(() -> model.chat(request)).isSameAs(quota);
        verify(gemini, times(1)).chat(request);
    }

    @Test
    void overloadedIsFoundAnywhereInTheCauses() {
        assertThat(RetryingChatLanguageModel.isModelOverloaded(
                new RuntimeException("Chat failed", new IllegalStateException("HTTP error (503): Service Unavailable")))).isTrue();
        assertThat(RetryingChatLanguageModel.isModelOverloaded(
                new RuntimeException("wrapped", new RuntimeException("{\"status\": \"UNAVAILABLE\"}")))).isTrue();
        // 503 като част от друго число или id не е претоварен модел
        assertThat(RetryingChatLanguageModel.isModelOverloaded(new RuntimeException("Too many tokens: 5030"))).isFalse();
        assertThat(RetryingChatLanguageModel.isModelOverloaded(new RuntimeException((String) null))).isFalse();
    }
}
