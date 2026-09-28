package com.hotel.langchain.log;

import com.hotel.langchain.model.ChatLog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Една заявка към booking-ai – основа за отчетите в админ страницата. Събира данните по време на заявката
// и накрая става отделен запис в logs_<hotelId> (ChatLog) или стъпка в действие от няколко заявки (ChatLog.Step).
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
    public static final String MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";
    public static final String HOTEL_LIMIT_MINUTE = "HOTEL_LIMIT_MINUTE";
    public static final String HOTEL_LIMIT_DAY = "HOTEL_LIMIT_DAY";

    // Текстовете на госта и асистента се пазят съкратени (лични данни)
    public static final int MAX_TEXT_LENGTH = 500;

    private final String type;
    private final Instant timestamp = Instant.now();
    private final long startNanos = System.nanoTime();
    private String userId;
    private String outcome = OK;
    private String errorType;
    private String userMessage;
    private String reply;
    private ChatLog.Gemini gemini;
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
            gemini = new ChatLog.Gemini();
            gemini.setCalls(usage.calls());
            gemini.setErrors(usage.errors());
            gemini.setInputTokens(usage.inputTokens());
            gemini.setOutputTokens(usage.outputTokens());
            if (!usage.tools().isEmpty()) {
                gemini.setTools(List.copyOf(usage.tools()));
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

    // Стъпка в действие (ChatFlow). Отговорът се пази само при неуспех – иначе е ясен от стъпката.
    ChatLog.Step toStep() {
        ChatLog.Step step = new ChatLog.Step();
        step.setStep(type);
        step.setAt(timestamp);
        step.setOutcome(outcome);
        step.setErrorType(errorType);
        step.setDurationMs(elapsedMs());
        step.setUserMessage(truncate(userMessage));
        step.setReply(OK.equals(outcome) ? null : truncate(reply));
        step.setGemini(gemini);
        step.setDetails(details.isEmpty() ? null : new LinkedHashMap<>(details));
        return step;
    }

    // Отделен запис
    ChatLog toChatLog(String hotelId) {
        ChatLog log = new ChatLog();
        log.setTimestamp(timestamp);
        log.setHotelId(hotelId);
        log.setUserId(userId);
        log.setType(type);
        log.setOutcome(outcome);
        log.setErrorType(errorType);
        log.setDurationMs(elapsedMs());
        log.setUserMessage(truncate(userMessage));
        log.setReply(truncate(reply));
        log.setGemini(gemini);
        log.setDetails(details.isEmpty() ? null : new LinkedHashMap<>(details));
        return log;
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "…";
    }
}
