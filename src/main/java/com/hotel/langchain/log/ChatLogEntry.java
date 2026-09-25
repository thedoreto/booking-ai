package com.hotel.langchain.log;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// Една заявка към booking-ai – основа за отчетите в админ страницата. Записва се по два начина:
// - отделен запис в logs_<hotelId> (въпрос в чата, бутон със знание):
//   { timestamp, hotelId, userId, type, outcome, errorType, durationMs, userMessage, reply, gemini, details }
// - стъпка в действие от няколко заявки (виж ChatFlow): { step, at, outcome, errorType, durationMs, ... }
public class ChatLogEntry {

    // type на отделен запис / step на стъпка в действие
    public static final String CHAT = "chat";
    public static final String SHORTCUT = "shortcut";
    public static final String MY_BOOKINGS = "my_bookings";
    public static final String SEARCH = "search";
    public static final String BOOKING = "booking";
    public static final String CANCEL = "cancel";

    // outcome
    public static final String OK = "ok";
    public static final String NO_RESULT = "no_result"; // няма свободни стаи, няма резервации, няма информация
    public static final String REJECTED = "rejected";   // невалидни данни или потребителят не е влязъл
    public static final String ERROR = "error";

    // errorType
    public static final String GEMINI_QUOTA_429 = "GEMINI_QUOTA_429";
    public static final String GEMINI_OVERLOADED_503 = "GEMINI_OVERLOADED_503";
    public static final String BACKEND_TIMEOUT = "BACKEND_TIMEOUT";
    public static final String BACKEND_ERROR = "BACKEND_ERROR";
    public static final String INTERNAL = "INTERNAL";

    // Текстовете на госта и асистента се пазят съкратени (лични данни)
    static final int MAX_TEXT_LENGTH = 500;

    private final String type;
    private final Instant timestamp = Instant.now();
    private final long startNanos = System.nanoTime();
    private String userId;
    private String outcome = OK;
    private String errorType;
    private String userMessage;
    private String reply;
    private Map<String, Object> gemini;
    private final Map<String, Object> details = new LinkedHashMap<>();

    private ChatLogEntry(String type) {
        this.type = type;
    }

    // Създава се в началото на заявката – durationMs се мери от този момент
    public static ChatLogEntry start(String type, String userId) {
        ChatLogEntry entry = new ChatLogEntry(type);
        entry.userId = userId != null && !userId.isBlank() ? userId : null;
        return entry;
    }

    public ChatLogEntry outcome(String outcome, String errorType) {
        this.outcome = outcome;
        this.errorType = errorType;
        return this;
    }

    public ChatLogEntry error(String errorType) {
        return outcome(ERROR, errorType);
    }

    public ChatLogEntry userMessage(String userMessage) {
        this.userMessage = userMessage;
        return this;
    }

    public ChatLogEntry reply(String reply) {
        this.reply = reply;
        return this;
    }

    // Извикванията, токените и tools на Gemini за заявката (null – заявката не е викала модела)
    public ChatLogEntry gemini(GeminiUsageTracker.Usage usage) {
        if (usage != null && usage.calls() > 0) {
            gemini = new LinkedHashMap<>();
            gemini.put("calls", usage.calls());
            gemini.put("errors", usage.errors());
            gemini.put("inputTokens", usage.inputTokens());
            gemini.put("outputTokens", usage.outputTokens());
            if (!usage.tools().isEmpty()) {
                gemini.put("tools", usage.tools());
            }
        }
        return this;
    }

    public ChatLogEntry detail(String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
        return this;
    }

    public String outcome() {
        return outcome;
    }

    String userId() {
        return userId;
    }

    Instant timestamp() {
        return timestamp;
    }

    Map<String, Object> gemini() {
        return gemini;
    }

    // Стъпка в действие (ChatFlow). Отговорът се пази само при неуспех – иначе е ясен от стъпката.
    Map<String, Object> toStepDocument() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", type);
        step.put("at", timestamp);
        step.put("outcome", outcome);
        if (errorType != null) {
            step.put("errorType", errorType);
        }
        step.put("durationMs", elapsedMs());
        if (userMessage != null) {
            step.put("userMessage", truncate(userMessage));
        }
        if (reply != null && !OK.equals(outcome)) {
            step.put("reply", truncate(reply));
        }
        if (gemini != null) {
            step.put("gemini", gemini);
        }
        step.putAll(details);
        return step;
    }

    Map<String, Object> toDocument(String hotelId) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("timestamp", timestamp);
        doc.put("hotelId", hotelId);
        doc.put("userId", userId);
        doc.put("type", type);
        doc.put("outcome", outcome);
        if (errorType != null) {
            doc.put("errorType", errorType);
        }
        doc.put("durationMs", elapsedMs());
        if (userMessage != null) {
            doc.put("userMessage", truncate(userMessage));
        }
        if (reply != null) {
            doc.put("reply", truncate(reply));
        }
        if (gemini != null) {
            doc.put("gemini", gemini);
        }
        if (!details.isEmpty()) {
            doc.put("details", details);
        }
        return doc;
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
