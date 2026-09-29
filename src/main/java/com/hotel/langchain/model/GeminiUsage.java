package com.hotel.langchain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

// Колко е ползван Gemini – брояч за един ден, източник и модел. В Mongo – в gemini_usage_<hotelId>
// (името на колекцията се подава от GeminiUsageRepository), всяко извикване добавя към документа на деня ($inc):
//   { _id, day: "2026-09-29", source: "chat", model: "gemini-3.6-flash",
//     calls: 41, errors: 1, inputTokens: 52300, outputTokens: 4100, updatedAt }
//   { _id, day: "2026-09-29", source: "chat_embedding", model: "gemini-embedding-001", calls: 40, errors: 0, characters: 2600, updatedAt }
// Денят е по тихоокеанско време – като дневната квота на Gemini и GeminiBudget.
// Токени има само при чат модела; embedding-ът не връща токени – при него се брои дължината на текста в знаци.
// Без цена: Gemini връща токените, не цената.
@Document
public class GeminiUsage {

    // Текстов въпрос в чата (Assistant)
    public static final String CHAT = "chat";
    // RAG търсенето за въпроса в чата
    public static final String CHAT_EMBEDDING = "chat_embedding";
    // Ново знание или променен текст в админ панела
    public static final String ADMIN_EMBEDDING = "admin_embedding";
    // Превод на знание в админ панела (KnowledgeTranslator)
    public static final String ADMIN_TRANSLATION = "admin_translation";
    // Анализ на въпросите за таб „Предложения“ в админ панела (SuggestionAnalyzer)
    public static final String ADMIN_ANALYSIS = "admin_analysis";

    @Id
    private String id;
    private String day;
    private String source;
    private String model;
    private Integer calls;
    private Integer errors;
    private Long inputTokens;
    private Long outputTokens;
    private Long characters;
    private Date updatedAt;

    // Извиквания на чат модела: токени, без знаци
    public static GeminiUsage tokens(String source, String model, int calls, int errors, long inputTokens, long outputTokens) {
        GeminiUsage usage = of(source, model, calls, errors);
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        return usage;
    }

    // Извиквания на embedding модела: знаци, без токени
    public static GeminiUsage characters(String source, String model, int calls, int errors, long characters) {
        GeminiUsage usage = of(source, model, calls, errors);
        usage.characters = characters;
        return usage;
    }

    private static GeminiUsage of(String source, String model, int calls, int errors) {
        GeminiUsage usage = new GeminiUsage();
        usage.source = source;
        usage.model = model;
        usage.calls = calls;
        usage.errors = errors;
        return usage;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getCalls() { return calls; }
    public void setCalls(Integer calls) { this.calls = calls; }
    public Integer getErrors() { return errors; }
    public void setErrors(Integer errors) { this.errors = errors; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public Long getCharacters() { return characters; }
    public void setCharacters(Long characters) { this.characters = characters; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
