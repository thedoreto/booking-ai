package com.hotel.langchain.log;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiUsageTrackerTest {

    private final GeminiUsageTracker tracker = new GeminiUsageTracker();

    @AfterEach
    void clear() {
        GeminiUsageTracker.clear();
    }

    @Test
    void countsCallsTokensToolsAndErrorsOfTheRequest() {
        GeminiUsageTracker.start();
        ToolExecutionRequest toolCall = ToolExecutionRequest.builder().id("1").name("showMyBookings").arguments("{}").build();

        tracker.onResponse(response(AiMessage.from(toolCall), new TokenUsage(100, 10)));
        tracker.onResponse(response(AiMessage.from("Ето резервациите ви."), new TokenUsage(150, 20)));
        tracker.onError(mock(ChatModelErrorContext.class));

        GeminiUsageTracker.Usage usage = GeminiUsageTracker.current();
        assertThat(usage.calls()).isEqualTo(3);
        assertThat(usage.errors()).isEqualTo(1);
        assertThat(usage.inputTokens()).isEqualTo(250);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.tools()).containsExactly("showMyBookings");
    }

    @Test
    void responseWithoutTokenUsageIsStillACall() {
        GeminiUsageTracker.start();

        tracker.onResponse(response(AiMessage.from("Здравейте"), null));

        assertThat(GeminiUsageTracker.current().calls()).isEqualTo(1);
        assertThat(GeminiUsageTracker.current().inputTokens()).isZero();
    }

    @Test
    void outsideARequestNothingIsCounted() {
        tracker.onResponse(response(AiMessage.from("Здравейте"), new TokenUsage(1, 1)));
        tracker.onError(mock(ChatModelErrorContext.class));

        assertThat(GeminiUsageTracker.current()).isNull();
    }

    private static ChatModelResponseContext response(AiMessage message, TokenUsage tokens) {
        ChatModelResponseContext context = mock(ChatModelResponseContext.class);
        when(context.chatResponse()).thenReturn(ChatResponse.builder().aiMessage(message).tokenUsage(tokens).build());
        return context;
    }
}
