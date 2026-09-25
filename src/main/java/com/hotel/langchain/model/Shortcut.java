package com.hotel.langchain.model;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.util.List;

@Document(collection = "shortcuts_#hotelId#") // Динамично мапване или без анотация, ако ползваш MongoTemplate с изрично име
public class Shortcut {

    @Id
    private String id;
    private String shortcutId;
    private String label;
    private String category;
    private String actionType;
    private List<ObjectId> targetKnowledgeIds;
    // В Mongo полето е is_active; липсващо поле = активен бутон
    @Field("is_active")
    private Boolean isActive;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getShortcutId() { return shortcutId; }
    public void setShortcutId(String shortcutId) { this.shortcutId = shortcutId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public List<ObjectId> getTargetKnowledgeIds() { return targetKnowledgeIds; }
    public void setTargetKnowledgeIds(List<ObjectId> targetKnowledgeIds) { this.targetKnowledgeIds = targetKnowledgeIds; }

    public boolean isActive() { return isActive == null || isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
