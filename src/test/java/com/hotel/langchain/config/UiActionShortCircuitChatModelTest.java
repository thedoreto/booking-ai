package com.hotel.langchain.config;

import com.hotel.langchain.context.TenantContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UiActionShortCircuitChatModelTest {

    private final ChatLanguageModel gemini = mock(ChatLanguageModel.class);
    private final UiActionShortCircuitChatModel model = new UiActionShortCircuitChatModel(gemini);
    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Искам стая")).build();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void afterToolAskedForUiActionGeminiIsNotCalledAgain() {
        TenantContext.requestUiAction(new TenantContext.UiAction("OPEN_DATE_PICKER", "Изберете дати", Map.of()));

        ChatResponse response = model.chat(request);

        assertThat(response.aiMessage().text()).isEqualTo("Изберете дати");
        verifyNoInteractions(gemini);
    }

    @Test
    void withoutUiActionTheRequestGoesToGemini() {
        ChatResponse geminiResponse = ChatResponse.builder().aiMessage(AiMessage.from("Здравейте")).build();
        when(gemini.chat(any(ChatRequest.class))).thenReturn(geminiResponse);

        assertThat(model.chat(request)).isSameAs(geminiResponse);
        verify(gemini).chat(request);
    }
}
