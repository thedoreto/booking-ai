package com.hotel.langchain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.util.List;

// Бутон в чата – само препратка: action казва към какво сочи (знание или tool).
// В Mongo (shortcuts_<hotelId>):
//   { shortcutId, label, category, isActive, action: { type: "knowledge", knowledgeIds: [...] } }
//   { shortcutId, label, category, isActive, action: { type: "tool", tool: "showMyBookings" } }
@Document(collection = "shortcuts_#hotelId#") // Динамично мапване или без анотация, ако ползваш MongoTemplate с изрично име
public class Shortcut {

    @Id
    private String id;
    private String shortcutId;
    private String label;
    private String category;
    // Липсващо поле = активен бутон
    @Field("isActive")
    private Boolean isActive;
    private Action action;

    public static class Action {
        public static final String KNOWLEDGE = "knowledge";
        public static final String TOOL = "tool";

        private String type;                // knowledge | tool
        private String tool;                // името на tool-а (@Tool метод), при type tool
        private List<ObjectId> knowledgeIds; // документи в knowledge_<hotelId>, при type knowledge

        @JsonIgnore
        public boolean isTool() { return TOOL.equals(type); }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        public List<ObjectId> getKnowledgeIds() { return knowledgeIds; }
        public void setKnowledgeIds(List<ObjectId> knowledgeIds) { this.knowledgeIds = knowledgeIds; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getShortcutId() { return shortcutId; }
    public void setShortcutId(String shortcutId) { this.shortcutId = shortcutId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public boolean isActive() { return isActive == null || isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    // Към какво сочи бутонът; null – записът няма action
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
}
