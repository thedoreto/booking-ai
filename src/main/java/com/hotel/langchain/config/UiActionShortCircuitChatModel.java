package com.hotel.langchain.config;

import com.hotel.langchain.context.TenantContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Set;

// Когато tool е поискал действие в UI (календар, избор на стаи), LangChain4j вика модела още веднъж
// само за да напише текст, който контролерът после изхвърля.
// Тук прекъсваме този цикъл и връщаме готовия отговор, без да викаме Gemini.
public class UiActionShortCircuitChatModel implements ChatLanguageModel {

    private final ChatLanguageModel delegate;

    public UiActionShortCircuitChatModel(ChatLanguageModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        TenantContext.UiAction uiAction = TenantContext.getUiAction();
        if (uiAction != null) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(uiAction.reply()))
                    .finishReason(FinishReason.STOP)
                    .build();
        }
        return delegate.chat(chatRequest);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return delegate.generate(messages);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }
}
