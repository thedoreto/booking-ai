package com.hotel.langchain.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.config.RetryingChatLanguageModel;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.service.KafkaService;
import com.hotel.langchain.service.ShortcutService;
import com.hotel.langchain.exception.OpenDatePickerException;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class AiLangChainController {

    private final Assistant assistant;
    private final ShortcutService shortcutService;
    private final MongoTemplate mongoTemplate;
    private final KafkaService kafkaService;

    private static final String KAFKA_TOPIC = "test-topic";

    public AiLangChainController(Assistant assistant,
                                 ShortcutService shortcutService,
                                 MongoTemplate mongoTemplate,
                                 KafkaService  kafkaService ) {
        this.assistant = assistant;
        this.shortcutService = shortcutService;
        this.mongoTemplate = mongoTemplate;
        this.kafkaService = kafkaService;
    }

    public record Message(String role, String content) {}

    public record ChatRequest(
            String hotelId,
            String userId,
            List<Message> messages,
            @JsonProperty("shortcutId") String shortcutId
    ) {}

    public record NewChatResponse(String reply, String actionType) {}

    @PostMapping("/chat")
    public NewChatResponse chat(@RequestBody ChatRequest request) {
        System.out.println("Received chat request: " + request);
        if (request == null) {
            return new NewChatResponse("Липсва заявка.", null);
        }

        try {
            setTenant(request.hotelId(), request.userId());
            System.out.println("Tenant set: hotelId=" + request.hotelId() + ", userId=" + request.userId());
            if (hasText(request.shortcutId())) {
                NewChatResponse response = handleShortcut(request.hotelId(), request.shortcutId());
                sendToKafka(request.hotelId(), "Shortcut triggered: " + request.shortcutId());
                return response;
            }

            if (request.messages() == null || request.messages().isEmpty()) {
                return new NewChatResponse("Липсват съобщения.", null);
            }

            Message lastMessage = request.messages().get(request.messages().size() - 1);
            String userText = lastMessage.content();

            LocalDate today = LocalDate.now();
            String formattedDate = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("bg", "BG"));
            String hotelId = request.hotelId();

            System.out.println("hotelId: " + hotelId + ", formattedDate: " + formattedDate + ", dayOfWeek: " + dayOfWeek + ", userText: " + userText);

            // Отделна история за всеки хотел и потребител
            String memoryId = hotelId + ":" + (hasText(request.userId()) ? request.userId() : "anonymous");
            String aiReply = assistant.chat(memoryId, hotelId, formattedDate, dayOfWeek, userText);

            sendToKafka(hotelId, "User message: " + userText + " | AI Reply: " + aiReply);

            String actionType = null;
            String finalReply = aiReply;

            if (TenantContext.isDatePickerRequested()) {
                actionType = OpenDatePickerException.OPEN_DATE_PICKER_ACTION;
                finalReply = OpenDatePickerException.DATE_PICKER_REPLY;
            }

            return new NewChatResponse(finalReply, actionType);

        } catch (Exception e) {
            if (isQuotaExceeded(e)) {
                log.warn("Gemini API quota exceeded for hotelId={}: {}", request.hotelId(), e.getMessage());
                return new NewChatResponse("Изчерпахте безплатните заявки към AI асистента. Моля, опитайте отново по-късно!", null);
            }
            if (RetryingChatLanguageModel.isModelOverloaded(e)) {
                log.warn("Gemini model overloaded for hotelId={}: {}", request.hotelId(), e.getMessage());
                return new NewChatResponse("В момента асистентът е претоварен. Моля, опитайте отново след минута.", null);
            }
            log.error("Chat failed for hotelId={}, userId={}", request.hotelId(), request.userId(), e);
            return new NewChatResponse("Възникна техническа грешка при връзката с асистента. Моля, опитайте по-късно.", null);
        } finally {
            TenantContext.clear();
        }
    }

    private void sendToKafka(String hotelId, String eventData) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("hotelId", hotelId);
        payload.put("event", eventData);

        kafkaService.send(KAFKA_TOPIC, hotelId, payload);
    }

    private void setTenant(String hotelId, String userId) {
        if (hotelId != null && !hotelId.isBlank()) {
            TenantContext.setHotelId(hotelId);
        }
        if (userId != null && !userId.isBlank()) {
            TenantContext.setUserId(userId);
        }
    }

    // Проверяваме цялата верига от причини, защото LangChain4j обвива HTTP грешката от Gemini
    private boolean isQuotaExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("429") || msg.contains("Too Many Requests")
                    || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("quota"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private NewChatResponse handleShortcut(String hotelId, String shortcutId) {
        System.out.println("hotelId: " + hotelId + "shortcutId: " + shortcutId);
        String shortcutsCollection = "shortcuts_" + hotelId;
        Shortcut shortcut = mongoTemplate.findOne(
                new Query(Criteria.where("shortcutId").is(shortcutId)),
                Shortcut.class,
                shortcutsCollection
        );

        if (shortcut == null || shortcut.getTargetKnowledgeIds() == null || shortcut.getTargetKnowledgeIds().isEmpty()) {
            return new NewChatResponse("Информацията не е намерена.", null);
        }

        String knowledgeId = shortcut.getTargetKnowledgeIds().get(0).toString();
        Document knowledgeDoc = mongoTemplate.findById(new ObjectId(knowledgeId), Document.class, "knowledge_" + hotelId);

        if (knowledgeDoc != null && knowledgeDoc.getString("text") != null) {
            return new NewChatResponse(knowledgeDoc.getString("text"), null);
        }

        return new NewChatResponse("Информацията не е намерена.", null);
    }

    @GetMapping("/shortcuts")
    public List<Shortcut> getShortcuts(@RequestParam String hotelId) {
        return shortcutService.getShortcutsForHotel(hotelId);
    }
}