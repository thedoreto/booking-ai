package com.hotel.langchain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

// Един анализ на въпросите от чата с Gemini (админ панел, таб „Предложения“) и предложенията от него.
// В Mongo – в suggestions_<hotelId> (името на колекцията се подава от SuggestionRepository):
//   { _id, createdAt, days: 30, questions: 84, inputTokens, outputTokens,
//     items: [ { id, type: "missing_knowledge", status: "new", topic, questions: [...], count, draft },
//              { id, type: "new_button", status: "dismissed", label, knowledgeIds: [...], questions: [...], count } ] }
// Показва се последният анализ; одобрените и отхвърлените от старите се пращат на Gemini като „вече разгледани“.
@Document
public class SuggestionAnalysis {

    // Въпроси без отговор в знанията – предложение за ново знание (черновата е с празни места [..] за фактите)
    public static final String MISSING_KNOWLEDGE = "missing_knowledge";
    // Чест въпрос, на който знанието отговаря – предложение за бутон към него
    public static final String NEW_BUTTON = "new_button";

    public static final String NEW = "new";
    public static final String ACCEPTED = "accepted";
    public static final String DISMISSED = "dismissed";

    @Id
    private String id;
    private Instant createdAt;
    // Периодът на анализираните въпроси (дни)
    private Integer days;
    // Колко различни въпроса са изпратени на Gemini
    private Integer questions;
    private Long inputTokens;
    private Long outputTokens;
    private List<Item> items;

    public static class Item {
        private String id;
        private String type;
        private String status;
        // missing_knowledge: темата и черновата на знанието
        private String topic;
        private String draft;
        // new_button: надписът (на езика по подразбиране на хотела) и знанията (_id в knowledge_<hotelId>)
        private String label;
        private List<String> knowledgeIds;
        // Примерните въпроси на гостите и колко пъти е питано по темата
        private List<String> questions;
        private Long count;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getDraft() { return draft; }
        public void setDraft(String draft) { this.draft = draft; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public List<String> getKnowledgeIds() { return knowledgeIds; }
        public void setKnowledgeIds(List<String> knowledgeIds) { this.knowledgeIds = knowledgeIds; }
        public List<String> getQuestions() { return questions; }
        public void setQuestions(List<String> questions) { this.questions = questions; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public Integer getQuestions() { return questions; }
    public void setQuestions(Integer questions) { this.questions = questions; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
}
