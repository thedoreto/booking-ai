package com.hotel.langchain.log;

import com.hotel.langchain.model.ChatLog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLogEntryTest {

    @Test
    void writesStructuredLog() {
        ChatLog log = ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1")
                .detail("roomType", "DOUBLE")
                .detail("roomsFound", 3)
                .reply("Свободни стаи")
                .toChatLog("seven_stars");

        assertThat(log.getTimestamp()).isNotNull();
        assertThat(log.getHotelId()).isEqualTo("seven_stars");
        assertThat(log.getUserId()).isEqualTo("user-1");
        assertThat(log.getType()).isEqualTo("search");
        assertThat(log.getOutcome()).isEqualTo("ok");
        assertThat(log.getReply()).isEqualTo("Свободни стаи");
        assertThat(log.getDetails()).isEqualTo(Map.of("roomType", "DOUBLE", "roomsFound", 3));
        assertThat(log.getErrorType()).isNull();
        assertThat(log.getUserMessage()).isNull();
        assertThat(log.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void recordsErrorTypeAndSkipsNullDetails() {
        ChatLog log = ChatLogEntry.start(ChatLogEntry.CHAT, "  ")
                .error(ChatLogEntry.GEMINI_QUOTA_429)
                .detail("actionType", null)
                .detail("tools", List.of("showMyBookings"))
                .toChatLog("40_robbers");

        assertThat(log.getUserId()).isNull();
        assertThat(log.getOutcome()).isEqualTo("error");
        assertThat(log.getErrorType()).isEqualTo("GEMINI_QUOTA_429");
        assertThat(log.getDetails()).isEqualTo(Map.of("tools", List.of("showMyBookings")));
    }

    @Test
    void truncatesLongTexts() {
        String longText = "а".repeat(ChatLogEntry.MAX_TEXT_LENGTH + 100);

        ChatLog log = ChatLogEntry.start(ChatLogEntry.CHAT, "user-1")
                .userMessage(longText)
                .reply("кратък")
                .toChatLog("seven_stars");

        assertThat(log.getUserMessage()).hasSize(ChatLogEntry.MAX_TEXT_LENGTH + 1).endsWith("…");
        assertThat(log.getReply()).isEqualTo("кратък");
    }

    @Test
    void writesStepWithReplyOnlyOnFailure() {
        ChatLog.Step ok = ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1")
                .detail("roomsFound", 3)
                .reply("Свободни стаи")
                .gemini(null)
                .toStep();

        assertThat(ok.getAt()).isNotNull();
        assertThat(ok.getStep()).isEqualTo("search");
        assertThat(ok.getOutcome()).isEqualTo("ok");
        assertThat(ok.getDetails()).containsEntry("roomsFound", 3);
        assertThat(ok.getReply()).isNull();
        assertThat(ok.getGemini()).isNull();
        assertThat(ok.getErrorType()).isNull();

        ChatLog.Step failed = ChatLogEntry.start(ChatLogEntry.BOOKING, "user-1")
                .outcome(ChatLogEntry.ERROR, ChatLogEntry.BACKEND_TIMEOUT)
                .reply("Хотелската система не потвърди резервацията навреме.")
                .toStep();

        assertThat(failed.getOutcome()).isEqualTo("error");
        assertThat(failed.getErrorType()).isEqualTo("BACKEND_TIMEOUT");
        assertThat(failed.getReply()).isEqualTo("Хотелската система не потвърди резервацията навреме.");
        assertThat(failed.getDetails()).isNull();
    }

    @Test
    void skipsGeminiWhenModelWasNotCalled() {
        ChatLog log = ChatLogEntry.start(ChatLogEntry.CHAT, "user-1")
                .gemini(new GeminiUsageTracker.Usage())
                .toChatLog("seven_stars");

        assertThat(log.getGemini()).isNull();
    }
}
