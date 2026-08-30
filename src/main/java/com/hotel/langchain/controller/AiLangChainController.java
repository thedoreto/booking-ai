package com.hotel.langchain.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.service.ShortcutService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class AiLangChainController {

    private final Assistant assistant;
    private final ShortcutService shortcutService;
    private final MongoTemplate mongoTemplate;

    public AiLangChainController(Assistant assistant, ShortcutService shortcutService, MongoTemplate mongoTemplate) {
        this.assistant = assistant;
        this.shortcutService = shortcutService;
        this.mongoTemplate = mongoTemplate;
    }

    public record Message(String role, String content) {}

    public record ChatRequest(
            String hotelId,
            List<Message> messages,
            @JsonProperty("shortcutId") String shortcutId
    ) {}

    public record NewChatResponse(String reply) {}

    @PostMapping("/chat")
    public NewChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null) {
            return new NewChatResponse("Липсва заявка.");
        }

        try {
            setTenant(request.hotelId());

            if (hasText(request.shortcutId())) {
                return handleShortcut(request.hotelId(), request.shortcutId());
            }

            if (request.messages() == null || request.messages().isEmpty()) {
                return new NewChatResponse("Липсват съобщения.");
            }

            String userText = request.messages().get(request.messages().size() - 1).content();
            LocalDate today = LocalDate.now();
            String formattedDate = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("bg", "BG"));
            return new NewChatResponse(assistant.chat(request.hotelId(), formattedDate, dayOfWeek, userText));

        } catch (Exception e) {
            return new NewChatResponse("Възникна техническа грешка при връзката с асистента. Моля, опитайте по-късно.");
        } finally {
            TenantContext.clear();
        }
    }

    private void setTenant(String hotelId) {
        if (hotelId != null && !hotelId.isBlank()) {
            TenantContext.setHotelId(hotelId);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private NewChatResponse handleShortcut(String hotelId, String shortcutId) {
        String shortcutsCollection = "shortcuts_" + hotelId;
        Shortcut shortcut = mongoTemplate.findOne(
                new Query(Criteria.where("shortcutId").is(shortcutId)),
                Shortcut.class,
                shortcutsCollection
        );

        if (shortcut == null || shortcut.getTargetKnowledgeIds() == null || shortcut.getTargetKnowledgeIds().isEmpty()) {
            return new NewChatResponse("Информацията не е намерена.");
        }

        String knowledgeId = shortcut.getTargetKnowledgeIds().get(0).toString();
        Document knowledgeDoc = mongoTemplate.findById(new ObjectId(knowledgeId), Document.class, "knowledge_" + hotelId);

        if (knowledgeDoc != null && knowledgeDoc.getString("text") != null) {
            return new NewChatResponse(knowledgeDoc.getString("text"));
        }

        return new NewChatResponse("Информацията не е намерена.");
    }

    @GetMapping("/shortcuts")
    public List<Shortcut> getShortcuts(@RequestParam String hotelId) {
        return shortcutService.getShortcutsForHotel(hotelId);
    }
}