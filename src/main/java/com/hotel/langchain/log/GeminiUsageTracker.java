package com.hotel.langchain.log;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;

// Брои извикванията и токените към Gemini и кои tools е поискал моделът – за текущата заявка (ThreadLocal).
// LangChain4j вика listener-ите синхронно, в нишката на заявката.
public class GeminiUsageTracker implements ChatModelListener {

    public static class Usage {
        private int calls;
        private int errors;
        private int inputTokens;
        private int outputTokens;
        private final List<String> tools = new ArrayList<>();

        public int calls() { return calls; }
        public int errors() { return errors; }
        public int inputTokens() { return inputTokens; }
        public int outputTokens() { return outputTokens; }
        public List<String> tools() { return tools; }
    }

    private static final ThreadLocal<Usage> CURRENT = new ThreadLocal<>();

    public static void start() {
        CURRENT.set(new Usage());
    }

    public static Usage current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        Usage usage = CURRENT.get();
        if (usage == null || context.chatResponse() == null) {
            return;
        }
        usage.calls++;
        TokenUsage tokens = context.chatResponse().tokenUsage();
        if (tokens != null) {
            usage.inputTokens += tokens.inputTokenCount() != null ? tokens.inputTokenCount() : 0;
            usage.outputTokens += tokens.outputTokenCount() != null ? tokens.outputTokenCount() : 0;
        }
        AiMessage message = context.chatResponse().aiMessage();
        if (message != null && message.hasToolExecutionRequests()) {
            for (ToolExecutionRequest request : message.toolExecutionRequests()) {
                usage.tools.add(request.name());
            }
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        Usage usage = CURRENT.get();
        if (usage != null) {
            usage.calls++;
            usage.errors++;
        }
    }
}
