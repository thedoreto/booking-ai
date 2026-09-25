package com.hotel.langchain.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.config.RetryingChatLanguageModel;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.log.ChatFlow;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.log.ChatLogService;
import com.hotel.langchain.log.GeminiUsageTracker;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.service.ChatHistoryService;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import com.hotel.langchain.service.ShortcutService;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class AiLangChainController {

    private static final String NOT_FOUND_REPLY = "Информацията не е намерена.";

    private final Assistant assistant;
    private final ShortcutService shortcutService;
    private final MongoTemplate mongoTemplate;
    private final ChatLogService chatLogService;
    private final RoomBookingService roomBookingService;
    private final RoomTypeService roomTypeService;

    public AiLangChainController(Assistant assistant,
                                 ShortcutService shortcutService,
                                 MongoTemplate mongoTemplate,
                                 ChatLogService chatLogService,
                                 RoomBookingService roomBookingService,
                                 RoomTypeService roomTypeService) {
        this.assistant = assistant;
        this.shortcutService = shortcutService;
        this.mongoTemplate = mongoTemplate;
        this.chatLogService = chatLogService;
        this.roomBookingService = roomBookingService;
        this.roomTypeService = roomTypeService;
    }

    public record Message(String role, String content) {}

    public record ChatRequest(
            String hotelId,
            String userId,
            List<Message> messages,
            @JsonProperty("shortcutId") String shortcutId,
            String flowId // текущата нова резервация в UI (виж ChatFlow), ако има
    ) {}

    // data: допълнителни данни за actionType (напр. списък стаи при SELECT_ROOMS)
    public record NewChatResponse(String reply, String actionType, Object data) {
        public NewChatResponse(String reply, String actionType) {
            this(reply, actionType, null);
        }
    }

    // flowId – действието (ChatFlow), към което е стъпката; без него започва ново
    public record AvailableRoomsRequest(String hotelId, String userId, String startDate, String endDate,
                                        String roomType, String flowId) {}

    public record CreateBookingRequest(String hotelId, String userId, String startDate, String endDate,
                                       List<String> roomIds, String flowId) {}

    public record MyBookingsRequest(String hotelId, String userId) {}

    public record CancelBookingRequest(String hotelId, String userId, String bookingId, String flowId) {}

    @PostMapping("/chat")
    public NewChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null) {
            return new NewChatResponse("Липсва заявка.", null);
        }
        // Без текста на съобщенията – той е в logs_<hotelId>, съкратен
        System.out.println("Received chat request: hotelId=" + request.hotelId() + ", userId=" + request.userId()
                + ", shortcutId=" + request.shortcutId()
                + ", messages=" + (request.messages() != null ? request.messages().size() : 0));
        if (!hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }

        try {
            setTenant(request.hotelId(), request.userId());
            if (hasText(request.shortcutId())) {
                return handleShortcut(request.hotelId(), request.userId(), request.shortcutId(), request.flowId());
            }

            if (request.messages() == null || request.messages().isEmpty()) {
                return new NewChatResponse("Липсват съобщения.", null);
            }

            return handleChat(request);
        } finally {
            TenantContext.clear();
        }
    }

    private NewChatResponse handleChat(ChatRequest request) {
        String hotelId = request.hotelId();
        Message lastMessage = request.messages().get(request.messages().size() - 1);
        String userText = lastMessage.content();
        ChatLogEntry logEntry = ChatLogEntry.start(ChatLogEntry.CHAT, request.userId()).userMessage(userText);
        GeminiUsageTracker.start();
        NewChatResponse response;

        try {
            LocalDate today = LocalDate.now();
            String formattedDate = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("bg", "BG"));

            System.out.println("hotelId: " + hotelId + ", formattedDate: " + formattedDate + ", dayOfWeek: " + dayOfWeek);

            // Отделна история за всеки хотел и потребител
            String memoryId = ChatHistoryService.memoryId(hotelId, request.userId());
            String roomTypes = roomTypeService.describeForPrompt(hotelId);
            String aiReply = assistant.chat(memoryId, hotelId, formattedDate, dayOfWeek, roomTypes, userText);

            // Tool е поискал действие в UI (календар, избор на стаи) – връщаме го вместо текста от модела
            TenantContext.UiAction uiAction = TenantContext.getUiAction();
            if (uiAction != null) {
                logEntry.outcome(uiAction.outcome(), uiAction.errorType());
                response = new NewChatResponse(uiAction.reply(), uiAction.actionType(), uiAction.data());
            } else {
                // Tool, който връща само текст на модела, е срещнал грешка (напр. timeout на бекенда)
                if (TenantContext.getToolError() != null) {
                    logEntry.error(TenantContext.getToolError());
                }
                response = new NewChatResponse(aiReply, null);
            }

        } catch (Exception e) {
            if (isQuotaExceeded(e)) {
                log.warn("Gemini API quota exceeded for hotelId={}: {}", hotelId, e.getMessage());
                logEntry.error(ChatLogEntry.GEMINI_QUOTA_429);
                response = new NewChatResponse("Изчерпахте безплатните заявки към AI асистента. Моля, опитайте отново по-късно!", null);
            } else if (RetryingChatLanguageModel.isModelOverloaded(e)) {
                log.warn("Gemini model overloaded for hotelId={}: {}", hotelId, e.getMessage());
                logEntry.error(ChatLogEntry.GEMINI_OVERLOADED_503);
                response = new NewChatResponse("В момента асистентът е претоварен. Моля, опитайте отново след минута.", null);
            } else {
                log.error("Chat failed for hotelId={}, userId={}", hotelId, request.userId(), e);
                logEntry.error(ChatLogEntry.INTERNAL);
                response = new NewChatResponse("Възникна техническа грешка при връзката с асистента. Моля, опитайте по-късно.", null);
            }
        }

        GeminiUsageTracker.Usage usage = GeminiUsageTracker.current();
        GeminiUsageTracker.clear();
        logEntry.reply(response.reply()).gemini(usage);
        return logOrStartFlow(hotelId, request.flowId(), ChatFlow.STARTED_BY_CHAT, logEntry, response);
    }

    // Календар или списък с резервации: заявката е първа стъпка в действие (ChatFlow) и UI получава flowId,
    // за да го върне със следващите стъпки. Всичко друго е отделен запис.
    // bookingFlowId – текущата нова резервация в UI; календарът я продължава, вместо да започне нова.
    private NewChatResponse logOrStartFlow(String hotelId, String bookingFlowId, String startedBy,
                                           ChatLogEntry logEntry, NewChatResponse response) {
        if (OpenDatePickerException.OPEN_DATE_PICKER_ACTION.equals(response.actionType())) {
            String flowId = ChatFlow.idOrNew(bookingFlowId);
            chatLogService.logStep(hotelId, flowId, ChatFlow.NEW_BOOKING, startedBy, ChatFlow.DATE_PICKER, logEntry);
            return withFlowId(response, flowId);
        }
        if (RoomBookingService.MY_BOOKINGS_ACTION.equals(response.actionType())) {
            // Всеки показан списък е ново действие – отказите от него са следващите стъпки
            String flowId = ChatFlow.idOrNew(null);
            chatLogService.logStep(hotelId, flowId, ChatFlow.CANCEL_BOOKING, startedBy, ChatFlow.BOOKINGS_SHOWN, logEntry);
            return withFlowId(response, flowId);
        }
        chatLogService.log(hotelId, logEntry);
        return response;
    }

    // Добавя flowId в data на отговора (data е Map или липсва; списък с резервации остава както е)
    private static NewChatResponse withFlowId(NewChatResponse response, String flowId) {
        Object data = response.data();
        if (data == null || data instanceof Map<?, ?>) {
            Map<String, Object> withFlow = new LinkedHashMap<>();
            if (data instanceof Map<?, ?> map) {
                map.forEach((key, value) -> withFlow.put(String.valueOf(key), value));
            }
            withFlow.put("flowId", flowId);
            data = withFlow;
        }
        return new NewChatResponse(response.reply(), response.actionType(), data);
    }

    private void setTenant(String hotelId, String userId) {
        if (hotelId != null && !hotelId.isBlank()) {
            TenantContext.setHotelId(hotelId);
        }
        if (userId != null && !userId.isBlank()) {
            TenantContext.setUserId(userId);
        }
    }

    // Проверяваме цялата верига от причини, защото LangChain4j обвива HTTP грешката от Gemini.
    // Gemini клиентът хвърля "HTTP error (429): <тяло>", а тялото при квота има статус RESOURCE_EXHAUSTED.
    // Не търсим само "429" – числото може да се появи и в друга грешка (id, брой токени).
    private boolean isQuotaExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("HTTP error (429)") || msg.contains("RESOURCE_EXHAUSTED"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private NewChatResponse handleShortcut(String hotelId, String userId, String shortcutId, String bookingFlowId) {
        System.out.println("hotelId: " + hotelId + ", shortcutId: " + shortcutId);
        ChatLogEntry logEntry = ChatLogEntry.start(ChatLogEntry.SHORTCUT, userId).detail("shortcutId", shortcutId);
        NewChatResponse response;
        try {
            response = shortcutResponse(hotelId, userId, shortcutId, logEntry);
        } catch (Exception e) {
            log.error("Shortcut failed for hotelId={}, shortcutId={}", hotelId, shortcutId, e);
            logEntry.error(ChatLogEntry.INTERNAL);
            response = new NewChatResponse("Възникна техническа грешка. Моля, опитайте по-късно.", null);
        }
        logEntry.reply(response.reply());
        return logOrStartFlow(hotelId, bookingFlowId, ChatFlow.STARTED_BY_BUTTON, logEntry, response);
    }

    private NewChatResponse shortcutResponse(String hotelId, String userId, String shortcutId, ChatLogEntry logEntry) {
        Shortcut shortcut = shortcutService.findActiveShortcut(hotelId, shortcutId);
        if (shortcut == null) {
            logEntry.outcome(ChatLogEntry.NO_RESULT, null);
            return new NewChatResponse(NOT_FOUND_REPLY, null);
        }
        logEntry.detail("shortcutType", shortcut.getActionType()).detail("label", shortcut.getLabel());

        // Бутон „Нова резервация“ – UI отваря календара сам; това е за клиенти, които все пак пращат shortcutId
        if ("open_date_picker".equals(shortcut.getActionType())) {
            return new NewChatResponse(OpenDatePickerException.DATE_PICKER_REPLY,
                    OpenDatePickerException.OPEN_DATE_PICKER_ACTION, Map.of());
        }
        // Бутон „Моите резервации“ – картички с бутон „Откажи“
        if ("my_bookings".equals(shortcut.getActionType())) {
            TenantContext.UiAction result = roomBookingService.myBookings(hotelId, userId);
            logEntry.outcome(result.outcome(), result.errorType());
            return new NewChatResponse(result.reply(), result.actionType(), result.data());
        }

        List<ObjectId> knowledgeIds = shortcut.getTargetKnowledgeIds();
        if (knowledgeIds == null || knowledgeIds.isEmpty() || knowledgeIds.get(0) == null) {
            logEntry.outcome(ChatLogEntry.NO_RESULT, null);
            return new NewChatResponse(NOT_FOUND_REPLY, null);
        }

        Document knowledgeDoc = mongoTemplate.findById(knowledgeIds.get(0), Document.class, "knowledge_" + hotelId);

        if (knowledgeDoc != null && knowledgeDoc.getString("text") != null) {
            return new NewChatResponse(knowledgeDoc.getString("text"), null);
        }

        logEntry.outcome(ChatLogEntry.NO_RESULT, null);
        return new NewChatResponse(NOT_FOUND_REPLY, null);
    }

    // Избрани дати от календара -> свободни стаи директно от booking-system, без LLM.
    // Стъпка в новата резервация (ChatFlow); без flowId – календарът е отворен от бутона.
    @PostMapping("/rooms/available")
    public NewChatResponse availableRooms(@RequestBody AvailableRoomsRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        String flowId = ChatFlow.idOrNew(request.flowId());
        ChatLogEntry step = ChatLogEntry.start(ChatLogEntry.SEARCH, request.userId())
                .detail("startDate", request.startDate())
                .detail("endDate", request.endDate())
                .detail("roomType", hasText(request.roomType()) ? request.roomType() : null);
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.findAvailableRooms(
                    request.hotelId(), request.startDate(), request.endDate(), request.roomType());
            if (result.data() instanceof Map<?, ?> data && data.get("rooms") instanceof List<?> rooms) {
                step.detail("roomsFound", rooms.size());
            }
            response = toResponse(step, result);
        } catch (Exception e) {
            log.error("Available rooms failed for hotelId={}", request.hotelId(), e);
            response = failure(step, "Възникна техническа грешка при търсене на стаи. Моля, опитайте по-късно.");
        }
        chatLogService.logStep(request.hotelId(), flowId, ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.searchStatus(step.outcome()), step);
        return withFlowId(response, flowId);
    }

    // Избрани стаи от списъка -> резервация директно в booking-system, без LLM. Стъпка в новата резервация.
    @PostMapping("/bookings")
    public NewChatResponse createBooking(@RequestBody CreateBookingRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        String flowId = ChatFlow.idOrNew(request.flowId());
        ChatLogEntry step = ChatLogEntry.start(ChatLogEntry.BOOKING, request.userId())
                .detail("startDate", request.startDate())
                .detail("endDate", request.endDate())
                .detail("roomIds", request.roomIds());
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.createBookings(request.hotelId(), request.userId(),
                    request.startDate(), request.endDate(), request.roomIds());
            if (result.data() instanceof List<?> bookings) {
                step.detail("bookingIds", bookings.stream().map(b -> field(b, "id")).toList())
                        .detail("nights", bookings.isEmpty() ? null : field(bookings.get(0), "nights"))
                        .detail("totalPrice", bookings.stream().mapToDouble(b -> number(field(b, "totalPrice"))).sum());
            }
            response = toResponse(step, result);
        } catch (Exception e) {
            log.error("Booking failed for hotelId={}, userId={}", request.hotelId(), request.userId(), e);
            response = failure(step, "Възникна техническа грешка при резервацията. Моля, опитайте по-късно.");
        }
        chatLogService.logStep(request.hotelId(), flowId, ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.bookingStatus(step.outcome()), step);
        return withFlowId(response, flowId);
    }

    // Предстоящите резервации на потребителя като картички (MY_BOOKINGS), без LLM.
    // Показан списък започва действие за отказ; празен списък или грешка е отделен запис.
    @PostMapping("/bookings/mine")
    public NewChatResponse myBookings(@RequestBody MyBookingsRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        ChatLogEntry logEntry = ChatLogEntry.start(ChatLogEntry.MY_BOOKINGS, request.userId());
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.myBookings(request.hotelId(), request.userId());
            if (result.data() instanceof Map<?, ?> data && data.get("bookings") instanceof List<?> bookings) {
                logEntry.detail("bookingsShown", bookings.size());
            }
            response = toResponse(logEntry, result);
        } catch (Exception e) {
            log.error("My bookings failed for hotelId={}, userId={}", request.hotelId(), request.userId(), e);
            response = failure(logEntry, "Възникна техническа грешка при зареждане на резервациите. Моля, опитайте по-късно.");
        }
        return logOrStartFlow(request.hotelId(), null, ChatFlow.STARTED_BY_BUTTON, logEntry, response);
    }

    // Бутон „Откажи“ на картичка -> отказ директно в booking-system, без LLM; записва се в паметта на чата.
    // Стъпка в действието за отказ, започнало с показания списък.
    @PostMapping("/bookings/cancel")
    public NewChatResponse cancelBooking(@RequestBody CancelBookingRequest request) {
        if (request == null || !hasText(request.hotelId())) {
            return new NewChatResponse("Липсва хотел.", null);
        }
        String flowId = ChatFlow.idOrNew(request.flowId());
        ChatLogEntry step = ChatLogEntry.start(ChatLogEntry.CANCEL, request.userId())
                .detail("bookingId", request.bookingId());
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.cancelBooking(
                    request.hotelId(), request.userId(), request.bookingId());
            if (result.data() != null) {
                step.detail("roomNumber", field(result.data(), "roomNumber"))
                        .detail("checkInDate", field(result.data(), "checkInDate"))
                        .detail("checkOutDate", field(result.data(), "checkOutDate"))
                        .detail("totalPrice", field(result.data(), "totalPrice"));
            }
            response = toResponse(step, result);
        } catch (Exception e) {
            log.error("Cancel booking failed for hotelId={}, userId={}", request.hotelId(), request.userId(), e);
            response = failure(step, "Възникна техническа грешка при отказа. Моля, опитайте по-късно.");
        }
        chatLogService.logStep(request.hotelId(), flowId, ChatFlow.CANCEL_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.cancelStatus(step.outcome()), step);
        return response;
    }

    // Изходът от RoomBookingService – в лога и в отговора за UI
    private static NewChatResponse toResponse(ChatLogEntry logEntry, TenantContext.UiAction result) {
        logEntry.outcome(result.outcome(), result.errorType()).reply(result.reply());
        return new NewChatResponse(result.reply(), result.actionType(), result.data());
    }

    private static NewChatResponse failure(ChatLogEntry logEntry, String reply) {
        logEntry.error(ChatLogEntry.INTERNAL).reply(reply);
        return new NewChatResponse(reply, null);
    }

    private static Object field(Object map, String key) {
        return map instanceof Map<?, ?> m ? m.get(key) : null;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
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
