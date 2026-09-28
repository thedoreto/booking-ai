package com.hotel.langchain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// Един запис за отчетите. В Mongo (logs_<hotelId> – името на колекцията се подава от ChatLogRepository), два вида:
// - отделна заявка (въпрос в чата, бутон със знание):
//   { timestamp, hotelId, userId, type, outcome, errorType, durationMs, userMessage, reply, gemini, details }
// - действие от няколко заявки (нова резервация, отказ) – един запис, който се допълва с всяка стъпка:
//   { flowId, type, hotelId, userId, startedBy, status, timestamp (начало), updatedAt, steps: [Step], gemini (сбор) }
// Полетата без стойност не се записват. Константите за type, outcome, status и т.н. са в ChatLogEntry и ChatFlow.
@Document
public class ChatLog {

    @Id
    private String id;
    private Instant timestamp;
    private String hotelId;
    private String userId;
    private String type;

    // Отделна заявка
    private String outcome;
    private String errorType;
    private Long durationMs;
    private String userMessage;
    private String reply;
    private Map<String, Object> details;

    // Действие от няколко заявки
    private String flowId;
    private String startedBy;
    private String status;
    private Instant updatedAt;
    private List<Step> steps;

    private Gemini gemini;

    // Стъпка в действие. Отговорът се пази само при неуспех – иначе е ясен от стъпката.
    public static class Step {
        private String step;
        private Instant at;
        private String outcome;
        private String errorType;
        private Long durationMs;
        private String userMessage;
        private String reply;
        private Gemini gemini;
        private Map<String, Object> details;

        public String getStep() { return step; }
        public void setStep(String step) { this.step = step; }
        public Instant getAt() { return at; }
        public void setAt(Instant at) { this.at = at; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }
        public Long getDurationMs() { return durationMs; }
        public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
        public String getUserMessage() { return userMessage; }
        public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
        public String getReply() { return reply; }
        public void setReply(String reply) { this.reply = reply; }
        public Gemini getGemini() { return gemini; }
        public void setGemini(Gemini gemini) { this.gemini = gemini; }
        public Map<String, Object> getDetails() { return details; }
        public void setDetails(Map<String, Object> details) { this.details = details; }
    }

    // Извикванията, токените и tools на Gemini. В действие – сборът от стъпките (без errors и tools).
    public static class Gemini {
        private Integer calls;
        private Integer errors;
        private Integer inputTokens;
        private Integer outputTokens;
        private List<String> tools;

        public Integer getCalls() { return calls; }
        public void setCalls(Integer calls) { this.calls = calls; }
        public Integer getErrors() { return errors; }
        public void setErrors(Integer errors) { this.errors = errors; }
        public Integer getInputTokens() { return inputTokens; }
        public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
        public Integer getOutputTokens() { return outputTokens; }
        public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getHotelId() { return hotelId; }
    public void setHotelId(String hotelId) { this.hotelId = hotelId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps; }
    public Gemini getGemini() { return gemini; }
    public void setGemini(Gemini gemini) { this.gemini = gemini; }
}
