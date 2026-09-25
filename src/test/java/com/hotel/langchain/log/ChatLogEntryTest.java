package com.hotel.langchain.log;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLogEntryTest {

    @Test
    void writesStructuredDocument() {
        Map<String, Object> doc = ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1")
                .detail("roomType", "DOUBLE")
                .detail("roomsFound", 3)
                .reply("Свободни стаи")
                .toDocument("seven_stars");

        assertThat(doc.get("timestamp")).isInstanceOf(Instant.class);
        assertThat(doc).containsEntry("hotelId", "seven_stars")
                .containsEntry("userId", "user-1")
                .containsEntry("type", "search")
                .containsEntry("outcome", "ok")
                .containsEntry("reply", "Свободни стаи")
                .containsEntry("details", Map.of("roomType", "DOUBLE", "roomsFound", 3))
                .doesNotContainKeys("errorType", "userMessage");
        assertThat((Long) doc.get("durationMs")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void recordsErrorTypeAndSkipsNullDetails() {
        Map<String, Object> doc = ChatLogEntry.start(ChatLogEntry.CHAT, "  ")
                .error(ChatLogEntry.GEMINI_QUOTA_429)
                .detail("actionType", null)
                .detail("tools", List.of("showMyBookings"))
                .toDocument("40_robbers");

        assertThat(doc).containsEntry("userId", null)
                .containsEntry("outcome", "error")
                .containsEntry("errorType", "GEMINI_QUOTA_429")
                .containsEntry("details", Map.of("tools", List.of("showMyBookings")));
    }

    @Test
    void truncatesLongTexts() {
        String longText = "а".repeat(ChatLogEntry.MAX_TEXT_LENGTH + 100);

        Map<String, Object> doc = ChatLogEntry.start(ChatLogEntry.CHAT, "user-1")
                .userMessage(longText)
                .reply("кратък")
                .toDocument("seven_stars");

        assertThat((String) doc.get("userMessage")).hasSize(ChatLogEntry.MAX_TEXT_LENGTH + 1).endsWith("…");
        assertThat(doc).containsEntry("reply", "кратък");
    }

    @Test
    void writesStepWithDetailsAtTopLevelAndReplyOnlyOnFailure() {
        Map<String, Object> ok = ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1")
                .detail("roomsFound", 3)
                .reply("Свободни стаи")
                .gemini(null)
                .toStepDocument();

        assertThat(ok.get("at")).isInstanceOf(Instant.class);
        assertThat(ok).containsEntry("step", "search")
                .containsEntry("outcome", "ok")
                .containsEntry("roomsFound", 3)
                .doesNotContainKeys("reply", "gemini", "details", "errorType");

        Map<String, Object> failed = ChatLogEntry.start(ChatLogEntry.BOOKING, "user-1")
                .outcome(ChatLogEntry.ERROR, ChatLogEntry.BACKEND_TIMEOUT)
                .reply("Хотелската система не потвърди резервацията навреме.")
                .toStepDocument();

        assertThat(failed).containsEntry("outcome", "error")
                .containsEntry("errorType", "BACKEND_TIMEOUT")
                .containsEntry("reply", "Хотелската система не потвърди резервацията навреме.");
    }

    @Test
    void skipsGeminiWhenModelWasNotCalled() {
        Map<String, Object> doc = ChatLogEntry.start(ChatLogEntry.CHAT, "user-1")
                .gemini(new GeminiUsageTracker.Usage())
                .toDocument("seven_stars");

        assertThat(doc).doesNotContainKey("gemini");
    }
}
