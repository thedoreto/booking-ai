package com.hotel.langchain.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

// Обвивка около Gemini модела: при 503 (претоварен модел) опитва отново с по-дълги паузи,
// защото вграденият retry на LangChain4j чака само ~0.5-1s между опитите.
public class RetryingChatLanguageModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(RetryingChatLanguageModel.class);

    private final ChatLanguageModel delegate;
    private final long[] retryDelaysMillis;

    public RetryingChatLanguageModel(ChatLanguageModel delegate, long... retryDelaysMillis) {
        this.delegate = delegate;
        this.retryDelaysMillis = retryDelaysMillis;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return withRetry(() -> delegate.chat(chatRequest));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return withRetry(() -> delegate.generate(messages));
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

    private <T> T withRetry(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (attempt >= retryDelaysMillis.length || !isModelOverloaded(e)) {
                    throw e;
                }
                long delay = retryDelaysMillis[attempt];
                log.warn("Gemini is overloaded (503), retry {}/{} in {} ms",
                        attempt + 1, retryDelaysMillis.length, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    // LangChain4j обвива HTTP грешката от Gemini, затова проверяваме цялата верига от причини
    public static boolean isModelOverloaded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("503") || msg.contains("UNAVAILABLE")
                    || msg.contains("high demand") || msg.contains("overloaded"))) {
                return true;
            }
        }
        return false;
    }
}
