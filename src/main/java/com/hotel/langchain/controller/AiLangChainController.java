package com.hotel.langchain.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.config.RetryingChatLanguageModel;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.service.ChatHistoryService;
import com.hotel.langchain.service.KafkaService;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import com.hotel.langchain.service.ShortcutService;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
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
    private final RoomBookingService roomBookingService;
    private final RoomTypeService roomTypeService;

    private static final String KAFKA_TOPIC = "test-topic";

    public AiLangChainController(Assistant assistant,
                                 ShortcutService shortcutService,
                                 MongoTemplate mongoTemplate,
                                 KafkaService  kafkaService,
                                 RoomBookingService roomBookingService,
                                 RoomTypeService roomTypeService) {
        this.assistant = assistant;
        this.shortcutService = shortcutService;
        this.mongoTemplate = mongoTemplate;
        this.kafkaService = kafkaService;
        this.roomBookingService = roomBookingService;
        this.roomTypeService = roomTypeService;
    }

    public record Message(String role, String content) {}

    public record ChatRequest(
            String hotelId,
            String userId,
            List<Message> messages,
            @JsonProperty("shortcutId") String shortcutId
    ) {}

    // data: допълнителни данни за actionType (напр. списък стаи при SELECT_ROOMS)
    public record NewChatResponse(String reply, String actionType, Object data) {
        public NewChatResponse(String reply, String actionType) {
            this(reply, actionType, null);
        }
    }

    public record AvailableRoomsRequest(String hotelId, String userId, String startDate, String endDate,
                                        String roomType) {}

    public record CreateBookingRequest(String hotelId, String userId, String startDate, String endDate,
                                       List<String> roomIds) {}

    public record MyBookingsRequest(String hotelId, String userId) {}

    public record CancelBookingRequest(String hotelId, String userId, String bookingId) {}

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
            String memoryId = ChatHistoryService.memoryId(hotelId, request.userId());
            String roomTypes = roomTypeService.describeForPrompt(hotelId);
            String aiReply = assistant.chat(memoryId, hotelId, formattedDate, dayOfWeek, roomTypes, userText);

            sendToKafka(hotelId, "User message: " + userText + " | AI Reply: " + aiReply);

            // Tool е поискал действие в UI (календар, избор на стаи) – връщаме го вместо текста от модела
            TenantContext.UiAction uiAction = TenantContext.getUiAction();
            if (uiAction != null) {
                return new NewChatResponse(uiAction.reply(), uiAction.actionType(), uiAction.data());
            }

            return new NewChatResponse(aiReply, null);

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
        Shortcut shortcut = shortcutService.findActiveShortcut(hotelId, shortcutId);

        // Бутон „Нова резервация“ – UI отваря календара сам; това е за клиенти, които все пак пращат shortcutId
        if (shortcut != null && "open_date_picker".equals(shortcut.getActionType())) {
            return new NewChatResponse(OpenDatePickerException.DATE_PICKER_REPLY,
                    OpenDatePickerException.OPEN_DATE_PICKER_ACTION, Map.of());
        }
        // Бутон „Моите резервации“ – картички с бутон „Откажи“
        if (shortcut != null && "my_bookings".equals(shortcut.getActionType())) {
            TenantContext.UiAction result = roomBookingService.myBookings(hotelId, TenantContext.getUserId());
            return new NewChatResponse(result.reply(), result.actionType(), result.data());
        }

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

    // Избрани дати от календара -> свободни стаи директно от booking-system, без LLM
    @PostMapping("/rooms/available")
    public NewChatResponse availableRooms(@RequestBody AvailableRoomsRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        try {
            TenantContext.UiAction result = roomBookingService.findAvailableRooms(
                    request.hotelId(), request.startDate(), request.endDate(), request.roomType());
            sendToKafka(request.hotelId(), "Available rooms " + request.startDate() + " - " + request.endDate()
                    + (hasText(request.roomType()) ? " type=" + request.roomType() : "")
                    + " | Reply: " + result.reply());
            return new NewChatResponse(result.reply(), result.actionType(), result.data());
        } catch (Exception e) {
            log.error("Available rooms failed for hotelId={}", request.hotelId(), e);
            return new NewChatResponse("Възникна техническа грешка при търсене на стаи. Моля, опитайте по-късно.", null);
        }
    }

    // Избрани стаи от списъка -> резервация директно в booking-system, без LLM
    @PostMapping("/bookings")
    public NewChatResponse createBooking(@RequestBody CreateBookingRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        try {
            TenantContext.UiAction result = roomBookingService.createBookings(request.hotelId(), request.userId(),
                    request.startDate(), request.endDate(), request.roomIds());
            sendToKafka(request.hotelId(), "Booking rooms " + request.roomIds() + " " + request.startDate()
                    + " - " + request.endDate() + " for userId=" + request.userId() + " | Reply: " + result.reply());
            return new NewChatResponse(result.reply(), result.actionType(), result.data());
        } catch (Exception e) {
            log.error("Booking failed for hotelId={}, userId={}", request.hotelId(), request.userId(), e);
            return new NewChatResponse("Възникна техническа грешка при резервацията. Моля, опитайте по-късно.", null);
        }
    }

    // Предстоящите резервации на потребителя като картички (MY_BOOKINGS), без LLM
    @PostMapping("/bookings/mine")
    public NewChatResponse myBookings(@RequestBody MyBookingsRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        try {
            TenantContext.UiAction result = roomBookingService.myBookings(request.hotelId(), request.userId());
            sendToKafka(request.hotelId(), "My bookings for userId=" + request.userId() + " | Reply: " + result.reply());
            return new NewChatResponse(result.reply(), result.actionType(), result.data());
        } catch (Exception e) {
            log.error("My bookings failed for hotelId={}, userId={}", request.hotelId(), request.userId(), e);
            return new NewChatResponse("Възникна техническа грешка при зареждане на резервациите. Моля, опитайте по-късно.", null);
        }
    }

    // Бутон „Откажи“ на картичка -> отказ директно в booking-system, без LLM; записва се в паметта на чата
    @PostMapping("/bookings/cancel")
    public NewChatResponse cancelBooking(@RequestBody CancelBookingRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        try {
            TenantContext.UiAction result = roomBookingService.cancelBooking(
                    request.hotelId(), request.userId(), request.bookingId());
            sendToKafka(request.hotelId(), "Cancel booking " + request.bookingId() + " for userId=" + request.userId()
                    + " | Reply: " + result.reply());
            return new NewChatResponse(result.reply(), result.actionType(), result.data());
        } catch (Exception e) {
            log.error("Cancel booking failed for hotelId={}, userId={}", request.hotelId(), request.userId(), e);
            return new NewChatResponse("Възникна техническа грешка при отказа. Моля, опитайте по-късно.", null);
        }
    }

    // Типовете стаи на хотела ({code, name}) – за избора в UI; идват от booking-system
    @GetMapping("/rooms/types")
    public List<RoomTypeService.RoomType> getRoomTypes(@RequestParam String hotelId) {
        return roomTypeService.getRoomTypes(hotelId);
    }

    @GetMapping("/shortcuts")
    public List<Shortcut> getShortcuts(@RequestParam String hotelId) {
        return shortcutService.getShortcutsForHotel(hotelId);
    }
}