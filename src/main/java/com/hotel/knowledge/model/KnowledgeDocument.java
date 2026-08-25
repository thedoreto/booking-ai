package com.hotel.knowledge.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.*;

@Document(collection = "knowledge")
public class KnowledgeDocument {

    @Id
    private String id;
    private String text;
    private String title;
    private String category;
    private List<String> tags;
    private String source;
    private List<Double> embedding;
    private Map<String, Object> metadata;

    public KnowledgeDocument() {
        // REQUIRED by Spring Data Mongo
    }

    public String getText() { return text; }
    public String getTitle() { return title; }
    public List<Double> getEmbedding() { return embedding; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getCategory() { return category; }
    public List<String> getTags() { return tags; }
    public String getSource() { return source; }
    public void setText(String text) { this.text = text; }
    public void setTitle(String title) { this.title = title; }
    public void setEmbedding(List<Double> embedding) { this.embedding = embedding; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public void setCategory(String category) { this.category = category; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public void setSource(String source) { this.source = source; }
}