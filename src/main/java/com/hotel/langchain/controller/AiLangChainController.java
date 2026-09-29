package com.hotel.langchain.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.config.RetryingChatLanguageModel;
import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.ChatUserResolver;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.log.ChatFlow;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.log.ChatLogService;
import com.hotel.langchain.log.GeminiUsageTracker;
import com.hotel.langchain.model.GeminiUsage;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.service.ChatHistoryService;
import com.hotel.langchain.service.GeminiBudget;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelRegistry;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import com.hotel.langchain.service.ShortcutService;
import com.hotel.langchain.service.Texts;
import com.hotel.langchain.service.TranslationService;
import com.hotel.langchain.tools.ShortcutToolRunner;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api")
public class AiLangChainController {

    // По-дълго съобщение не стига до Gemini (токени от общата квота). Колкото дължината в логовете –
    // всеки въпрос, който минава, се записва цял.
    private static final int MAX_MESSAGE_LENGTH = ChatLogEntry.MAX_TEXT_LENGTH;

    private final Assistant assistant;
    private final ShortcutService shortcutService;
    private final KnowledgeService knowledgeService;
    private final ShortcutToolRunner shortcutToolRunner;
    private final ChatLogService chatLogService;
    private final RoomBookingService roomBookingService;
    private final RoomTypeService roomTypeService;
    private final HotelRegistry hotelRegistry;
    private final GeminiBudget geminiBudget;
    private final ChatUserResolver chatUserResolver;
    private final HotelLanguages hotelLanguages;
    private final TranslationService translations;

    public AiLangChainController(Assistant assistant,
                                 ShortcutService shortcutService,
                                 KnowledgeService knowledgeService,
                                 ShortcutToolRunner shortcutToolRunner,
                                 ChatLogService chatLogService,
                                 RoomBookingService roomBookingService,
                                 RoomTypeService roomTypeService,
                                 HotelRegistry hotelRegistry,
                                 GeminiBudget geminiBudget,
                                 ChatUserResolver chatUserResolver,
                                 HotelLanguages hotelLanguages,
                                 TranslationService translations) {
        this.assistant = assistant;
        this.shortcutService = shortcutService;
        this.knowledgeService = knowledgeService;
        this.shortcutToolRunner = shortcutToolRunner;
        this.chatLogService = chatLogService;
        this.roomBookingService = roomBookingService;
        this.roomTypeService = roomTypeService;
        this.hotelRegistry = hotelRegistry;
        this.geminiBudget = geminiBudget;
        this.chatUserResolver = chatUserResolver;
        this.hotelLanguages = hotelLanguages;
        this.translations = translations;
    }

    public record Message(String role, String content) {}

    // Потребителят не идва от body-то, а от проверения JWT в header Authorization (виж ChatUserResolver)
    public record ChatRequest(
            String hotelId,
            List<Message> messages,
            @JsonProperty("shortcutId") String shortcutId,
            String flowId, // текущата нова резервация в UI (виж ChatFlow), ако има
            String sessionId // разговорът в UI (UUID) – отделна памет за всеки гост
    ) {}

    // data: допълнителни данни за actionType (напр. списък стаи при SELECT_ROOMS)
    public record NewChatResponse(String reply, String actionType, Object data) {
        public NewChatResponse(String reply, String actionType) {
            this(reply, actionType, null);
        }
    }

    // flowId – действието (ChatFlow), към което е стъпката; без него започва ново
    public record AvailableRoomsRequest(String hotelId, String startDate, String endDate,
                                        String roomType, String flowId) {}

    public record CreateBookingRequest(String hotelId, String startDate, String endDate,
                                       List<String> roomIds, String flowId) {}

    public record MyBookingsRequest(String hotelId) {}

    public record CancelBookingRequest(String hotelId, String bookingId, String flowId) {}

    // Бутон в чата, както го вижда UI: етикетът на избрания език; какво прави бутонът, UI не знае (action остава в booking-ai)
    public record ShortcutButton(String shortcutId, String label, String category) {}

    // languages – за менюто с езици в чата (в този ред); language – езикът, на който отговаряме;
    // texts – текстовете на прозореца на чата на този език (ключове ui.* от translations)
    public record ChatSettings(List<HotelLanguages.Language> languages, String language, Map<String, String> texts) {}

    @PostMapping("/chat")
    public NewChatResponse chat(@RequestBody ChatRequest request,
                                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        if (request == null) {
            return new NewChatResponse(commonTexts(acceptLanguage).message("chat.missingRequest"), null);
        }
        NewChatResponse hotelError = hotelError(request.hotelId(), acceptLanguage);
        if (hotelError != null) {
            return hotelError;
        }
        ChatUser user = chatUserResolver.resolve(request.hotelId(), authorization);
        // Без текста на съобщенията – той е в logs_<hotelId>, съкратен
        System.out.println("Received chat request: hotelId=" + request.hotelId() + ", userId=" + userIdOf(user)
                + ", shortcutId=" + request.shortcutId()
                + ", messages=" + (request.messages() != null ? request.messages().size() : 0));

        try {
            setTenant(request.hotelId(), user);
            TenantContext.setLanguage(hotelLanguages.resolve(request.hotelId(), acceptLanguage));
            if (hasText(request.shortcutId())) {
                return handleShortcut(request.hotelId(), user, request.shortcutId(), request.flowId());
            }

            if (request.messages() == null || request.messages().isEmpty()) {
                return new NewChatResponse(text("chat.missingMessages"), null);
            }

            return handleChat(request, user);
        } finally {
            TenantContext.clear();
        }
    }

    private NewChatResponse handleChat(ChatRequest request, ChatUser user) {
        String hotelId = request.hotelId();
        Message lastMessage = request.messages().get(request.messages().size() - 1);
        String userText = lastMessage != null ? lastMessage.content() : null;
        if (!hasText(userText)) {
            return new NewChatResponse(text("chat.missingMessages"), null);
        }
        ChatLogEntry logEntry = ChatLogEntry.start(ChatLogEntry.CHAT, userIdOf(user)).userMessage(userText);
        if (userText.length() > MAX_MESSAGE_LENGTH) {
            String reply = texts().message("chat.messageTooLong", Map.of("max", MAX_MESSAGE_LENGTH));
            logEntry.outcome(ChatLogEntry.REJECTED, ChatLogEntry.MESSAGE_TOO_LONG)
                    .detail("length", userText.length())
                    .reply(reply);
            chatLogService.log(hotelId, logEntry);
            return new NewChatResponse(reply, null);
        }
        GeminiBudget.Result budget = geminiBudget.tryAcquire(hotelId);
        if (!budget.allowed()) {
            return overBudget(hotelId, budget, logEntry);
        }
        GeminiUsageTracker.start();
        NewChatResponse response;

        try {
            LocalDate today = LocalDate.now();
            String formattedDate = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("bg", "BG"));

            System.out.println("hotelId: " + hotelId + ", formattedDate: " + formattedDate + ", dayOfWeek: " + dayOfWeek);

            // Отделна история за всеки хотел и потребител
            String memoryId = ChatHistoryService.memoryId(hotelId, user, request.sessionId());
            String roomTypes = roomTypeService.describeForPrompt(hotelId);
            String language = hotelLanguages.nameOf(hotelId, TenantContext.getLanguage());
            String aiReply = assistant.chat(memoryId, hotelId, formattedDate, dayOfWeek, roomTypes, language, userText);

            // Tool е поискал действие в UI (календар, избор на стаи) – връщаме го вместо текста от модела
            TenantContext.UiAction uiAction = TenantContext.getUiAction();
            if (uiAction != null) {
                logEntry.outcome(uiAction.outcome(), uiAction.errorType());
                response = new NewChatResponse(uiAction.reply(), uiAction.actionType(), uiAction.data());
            } else {
                // Tool, който връща само текст на модела, е срещнал грешка (напр. timeout на бекенда)
                if (TenantContext.getToolError() != null) {
                    logEntry.error(TenantContext.getToolError());
                } else if (aiReply != null && aiReply.contains(Assistant.NO_INFO_MARKER)) {
                    // Gemini няма отговора в знанията – „въпрос без отговор“ в отчетите
                    logEntry.outcome(ChatLogEntry.NO_RESULT, null);
                }
                response = new NewChatResponse(withoutNoInfoMarker(aiReply), null);
            }

        } catch (Exception e) {
            if (isQuotaExceeded(e)) {
                log.warn("Gemini API quota exceeded for hotelId={}: {}", hotelId, e.getMessage());
                logEntry.error(ChatLogEntry.GEMINI_QUOTA_429);
                response = new NewChatResponse(text("chat.geminiQuotaExhausted"), null);
            } else if (RetryingChatLanguageModel.isModelOverloaded(e)) {
                log.warn("Gemini model overloaded for hotelId={}: {}", hotelId, e.getMessage());
                logEntry.error(ChatLogEntry.GEMINI_OVERLOADED_503);
                response = new NewChatResponse(text("chat.geminiOverloaded"), null);
            } else {
                log.error("Chat failed for hotelId={}, userId={}", hotelId, userIdOf(user), e);
                logEntry.error(ChatLogEntry.INTERNAL);
                response = new NewChatResponse(text("chat.geminiError"), null);
            }
        }

        GeminiUsageTracker.Usage usage = GeminiUsageTracker.current();
        GeminiUsageTracker.clear();
        if (usage != null) {
            chatLogService.geminiUsage(hotelId, GeminiUsage.tokens(GeminiUsage.CHAT, usage.model(),
                    usage.calls(), usage.errors(), usage.inputTokens(), usage.outputTokens()));
        }
        if (TenantContext.getKnowledgeScores() != null) {
            logEntry.detail("knowledgeScores", TenantContext.getKnowledgeScores());
        }
        logEntry.reply(response.reply()).gemini(usage);
        return logOrStartFlow(hotelId, request.flowId(), ChatFlow.STARTED_BY_CHAT, logEntry, response);
    }

    // Маркерът не стига до госта – където и да го е сложил Gemini
    private static String withoutNoInfoMarker(String reply) {
        return reply == null || !reply.contains(Assistant.NO_INFO_MARKER)
                ? reply : reply.replace(Assistant.NO_INFO_MARKER, "").trim();
    }

    // Хотелът е стигнал лимита на съобщенията към Gemini (GeminiBudget) – бутоните продължават да работят.
    // В логовете се записва само първият отказ в прозореца, за да не ги пълни скрипт.
    private NewChatResponse overBudget(String hotelId, GeminiBudget.Result budget, ChatLogEntry logEntry) {
        boolean daily = budget.limit() == GeminiBudget.Limit.DAY;
        String reply = text(daily ? "chat.hotelLimitDay" : "chat.hotelLimitMinute");
        if (budget.firstRejection()) {
            System.out.println("Gemini limit reached for hotelId=" + hotelId + ": " + budget.limit());
            logEntry.outcome(ChatLogEntry.REJECTED, daily ? ChatLogEntry.HOTEL_LIMIT_DAY : ChatLogEntry.HOTEL_LIMIT_MINUTE)
                    .reply(reply);
            chatLogService.log(hotelId, logEntry);
        }
        return new NewChatResponse(reply, null);
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
            // От бутона, от чата или от /bookings/mine – колко резервации са показани
            if (response.data() instanceof Map<?, ?> data && data.get("bookings") instanceof List<?> bookings) {
                logEntry.detail("bookingsShown", bookings.size());
            }
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

    private void setTenant(String hotelId, ChatUser user) {
        if (hotelId != null && !hotelId.isBlank()) {
            TenantContext.setHotelId(hotelId);
        }
        TenantContext.setUser(user);
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

    // Провереният userId от токена (ChatUserResolver); null – гост
    private static String userIdOf(ChatUser user) {
        return user != null ? user.id() : null;
    }

    // null – хотелът съществува; иначе отговорът за UI. Непознат хотел не стига до логовете
    // (не създава logs_<hotelId>), Gemini и Kafka – виж HotelRegistry.
    // Хотелът още не е проверен – езикът е поисканият, без да се сверява с езиците на хотела
    private NewChatResponse hotelError(String hotelId, String acceptLanguage) {
        if (!hasText(hotelId)) {
            return new NewChatResponse(commonTexts(acceptLanguage).message("chat.missingHotel"), null);
        }
        if (!hotelRegistry.isKnown(hotelId)) {
            return new NewChatResponse(commonTexts(acceptLanguage).message("chat.unknownHotel"), null);
        }
        return null;
    }

    // Само общите преводи на поискания език – хотелът не е проверен и не се пази слой за измислен hotelId
    private Texts commonTexts(String acceptLanguage) {
        return translations.forRequest(null, acceptLanguage == null ? null : acceptLanguage.trim().toLowerCase(Locale.ROOT));
    }

    // Текстовете на хотела и езика на текущата заявка към /api/chat (TenantContext)
    private Texts texts() {
        return translations.forRequest(TenantContext.getHotelId(), TenantContext.getLanguage());
    }

    private String text(String key) {
        return texts().message(key);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private NewChatResponse handleShortcut(String hotelId, ChatUser user, String shortcutId, String bookingFlowId) {
        System.out.println("hotelId: " + hotelId + ", shortcutId: " + shortcutId);
        ChatLogEntry logEntry = ChatLogEntry.start(ChatLogEntry.SHORTCUT, userIdOf(user)).detail("shortcutId", shortcutId);
        NewChatResponse response;
        try {
            response = shortcutResponse(hotelId, user, shortcutId, logEntry);
        } catch (Exception e) {
            log.error("Shortcut failed for hotelId={}, shortcutId={}", hotelId, shortcutId, e);
            logEntry.error(ChatLogEntry.INTERNAL);
            response = new NewChatResponse(text("common.technicalError"), null);
        }
        logEntry.reply(response.reply());
        return logOrStartFlow(hotelId, bookingFlowId, ChatFlow.STARTED_BY_BUTTON, logEntry, response);
    }

    private NewChatResponse shortcutResponse(String hotelId, ChatUser user, String shortcutId, ChatLogEntry logEntry) {
        Shortcut shortcut = shortcutService.findActiveShortcut(hotelId, shortcutId);
        // Бутон, скрит от госта (guest.isActive: false), не се изпълнява и при директна заявка
        if (shortcut != null && user == null && !shortcut.isVisibleToGuest()) {
            shortcut = null;
        }
        if (shortcut == null) {
            logEntry.outcome(ChatLogEntry.NO_RESULT, null);
            return new NewChatResponse(text("chat.notFound"), null);
        }
        // В логовете – на езика по подразбиране, за да са отчетите еднакви независимо от езика на госта
        String label = shortcut.labelIn(null, hotelLanguages.of(hotelId).defaultLanguage());
        Shortcut.Action action = shortcut.getAction();
        if (action == null) {
            logEntry.detail("label", label).outcome(ChatLogEntry.NO_RESULT, null);
            return new NewChatResponse(text("chat.notFound"), null);
        }
        logEntry.detail("label", label)
                .detail("actionType", action.getType())
                .detail("tool", action.getTool());

        // Бутон с tool – същият tool, който Gemini вика от чата, но без Gemini
        if (action.isTool()) {
            Optional<String> toolReply = shortcutToolRunner.run(action.getTool());
            TenantContext.UiAction uiAction = TenantContext.getUiAction();
            if (uiAction != null) {
                // Tool-ът е поискал действие в UI (календар, списък с резервации...)
                return toResponse(logEntry, uiAction);
            }
            if (toolReply.isEmpty() || toolReply.get().isBlank()) {
                logEntry.outcome(ChatLogEntry.NO_RESULT, null);
                return new NewChatResponse(text("chat.notFound"), null);
            }
            if (TenantContext.getToolError() != null) {
                logEntry.error(TenantContext.getToolError());
            }
            return new NewChatResponse(toolReply.get(), null);
        }

        // Бутон със знание – текстовете на документите директно от knowledge_<hotelId>, без vector search,
        // на езика на чата, ако има превод
        List<String> texts = knowledgeService.textsByIds(hotelId, action.getKnowledgeIds(), TenantContext.getLanguage());
        if (!texts.isEmpty()) {
            return new NewChatResponse(String.join("\n\n", texts), null);
        }

        logEntry.outcome(ChatLogEntry.NO_RESULT, null);
        return new NewChatResponse(text("chat.notFound"), null);
    }

    // Избрани дати от календара -> свободни стаи директно от booking-system, без LLM.
    // Стъпка в новата резервация (ChatFlow); без flowId – календарът е отворен от бутона.
    @PostMapping("/rooms/available")
    public NewChatResponse availableRooms(@RequestBody AvailableRoomsRequest request, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                          @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        NewChatResponse hotelError = hotelError(request != null ? request.hotelId() : null, acceptLanguage);
        if (hotelError != null) {
            return hotelError;
        }
        String language = hotelLanguages.resolve(request.hotelId(), acceptLanguage);
        String flowId = ChatFlow.idOrNew(request.flowId());
        ChatLogEntry step = ChatLogEntry.start(ChatLogEntry.SEARCH, userIdOf(chatUserResolver.resolve(request.hotelId(), authorization)))
                .detail("startDate", request.startDate())
                .detail("endDate", request.endDate())
                .detail("roomType", hasText(request.roomType()) ? request.roomType() : null);
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.findAvailableRooms(
                    request.hotelId(), request.startDate(), request.endDate(), request.roomType(), language);
            if (result.data() instanceof Map<?, ?> data && data.get("rooms") instanceof List<?> rooms) {
                step.detail("roomsFound", rooms.size());
            }
            response = toResponse(step, result);
        } catch (Exception e) {
            log.error("Available rooms failed for hotelId={}", request.hotelId(), e);
            response = failure(step, translations.forRequest(request.hotelId(), language).message("rooms.searchFailed"));
        }
        chatLogService.logStep(request.hotelId(), flowId, ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.searchStatus(step.outcome()), step);
        return withFlowId(response, flowId);
    }

    // Избрани стаи от списъка -> резервация директно в booking-system, без LLM. Стъпка в новата резервация.
    @PostMapping("/bookings")
    public NewChatResponse createBooking(@RequestBody CreateBookingRequest request, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                         @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        NewChatResponse hotelError = hotelError(request != null ? request.hotelId() : null, acceptLanguage);
        if (hotelError != null) {
            return hotelError;
        }
        String language = hotelLanguages.resolve(request.hotelId(), acceptLanguage);
        ChatUser user = chatUserResolver.resolve(request.hotelId(), authorization);
        String flowId = ChatFlow.idOrNew(request.flowId());
        ChatLogEntry step = ChatLogEntry.start(ChatLogEntry.BOOKING, userIdOf(user))
                .detail("startDate", request.startDate())
                .detail("endDate", request.endDate())
                .detail("roomIds", request.roomIds());
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.createBookings(request.hotelId(), user,
                    request.startDate(), request.endDate(), request.roomIds(), language);
            if (result.data() instanceof List<?> bookings) {
                step.detail("bookingIds", bookings.stream().map(b -> field(b, "id")).toList())
                        .detail("nights", bookings.isEmpty() ? null : field(bookings.get(0), "nights"))
                        .detail("totalPrice", bookings.stream().mapToDouble(b -> number(field(b, "totalPrice"))).sum());
            }
            response = toResponse(step, result);
        } catch (Exception e) {
            log.error("Booking failed for hotelId={}, userId={}", request.hotelId(), userIdOf(user), e);
            response = failure(step, translations.forRequest(request.hotelId(), language).message("booking.failed"));
        }
        chatLogService.logStep(request.hotelId(), flowId, ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.bookingStatus(step.outcome()), step);
        return withFlowId(response, flowId);
    }

    // Предстоящите резервации на потребителя като картички (MY_BOOKINGS), без LLM.
    // Показан списък започва действие за отказ; празен списък или грешка е отделен запис.
    @PostMapping("/bookings/mine")
    public NewChatResponse myBookings(@RequestBody MyBookingsRequest request, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                      @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        NewChatResponse hotelError = hotelError(request != null ? request.hotelId() : null, acceptLanguage);
        if (hotelError != null) {
            return hotelError;
        }
        String language = hotelLanguages.resolve(request.hotelId(), acceptLanguage);
        ChatUser user = chatUserResolver.resolve(request.hotelId(), authorization);
        ChatLogEntry logEntry = ChatLogEntry.start(ChatLogEntry.MY_BOOKINGS, userIdOf(user));
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.myBookings(request.hotelId(), user, language);
            response = toResponse(logEntry, result);
        } catch (Exception e) {
            log.error("My bookings failed for hotelId={}, userId={}", request.hotelId(), userIdOf(user), e);
            response = failure(logEntry, translations.forRequest(request.hotelId(), language).message("myBookings.failed"));
        }
        return logOrStartFlow(request.hotelId(), null, ChatFlow.STARTED_BY_BUTTON, logEntry, response);
    }

    // Бутон „Откажи“ на картичка -> отказ директно в booking-system, без LLM; записва се в паметта на чата.
    // Стъпка в действието за отказ, започнало с показания списък.
    @PostMapping("/bookings/cancel")
    public NewChatResponse cancelBooking(@RequestBody CancelBookingRequest request, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                         @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        NewChatResponse hotelError = hotelError(request != null ? request.hotelId() : null, acceptLanguage);
        if (hotelError != null) {
            return hotelError;
        }
        String language = hotelLanguages.resolve(request.hotelId(), acceptLanguage);
        ChatUser user = chatUserResolver.resolve(request.hotelId(), authorization);
        String flowId = ChatFlow.idOrNew(request.flowId());
        ChatLogEntry step = ChatLogEntry.start(ChatLogEntry.CANCEL, userIdOf(user))
                .detail("bookingId", request.bookingId());
        NewChatResponse response;
        try {
            TenantContext.UiAction result = roomBookingService.cancelBooking(
                    request.hotelId(), user, request.bookingId(), language);
            if (result.data() != null) {
                step.detail("roomNumber", field(result.data(), "roomNumber"))
                        .detail("checkInDate", field(result.data(), "checkInDate"))
                        .detail("checkOutDate", field(result.data(), "checkOutDate"))
                        .detail("totalPrice", field(result.data(), "totalPrice"));
            }
            response = toResponse(step, result);
        } catch (Exception e) {
            log.error("Cancel booking failed for hotelId={}, userId={}", request.hotelId(), userIdOf(user), e);
            response = failure(step, translations.forRequest(request.hotelId(), language).message("cancel.failed"));
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

    // Типовете стаи на хотела ({code, name}) – за избора в UI; идват от booking-system, името – преведено
    // на езика от Accept-Language (translations_<hotelId>, после translations), без превод – както е дошло
    @GetMapping("/rooms/types")
    public List<RoomTypeService.RoomType> getRoomTypes(@RequestParam String hotelId,
                                                       @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        if (!hotelRegistry.isKnown(hotelId)) {
            return List.of();
        }
        Texts texts = translations.forRequest(hotelId, hotelLanguages.resolve(hotelId, acceptLanguage));
        return roomTypeService.getRoomTypes(hotelId).stream()
                .map(type -> new RoomTypeService.RoomType(type.code(), texts.translate(type.name())))
                .toList();
    }

    // Настройките на чата за хотела: езиците и избраният от тях (header Accept-Language; непознат – езикът по подразбиране)
    @GetMapping("/chat/settings")
    public ChatSettings getChatSettings(@RequestParam String hotelId,
                                        @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String language) {
        if (!hotelRegistry.isKnown(hotelId)) {
            return new ChatSettings(List.of(), null, Map.of());
        }
        String resolved = hotelLanguages.resolve(hotelId, language);
        return new ChatSettings(hotelLanguages.of(hotelId).languages(), resolved,
                translations.forRequest(hotelId, resolved).withPrefix("ui."));
    }

    // Без токен (гост) не се връщат бутоните с guest.isActive: false. Етикетът – на езика от Accept-Language,
    // без превод – на езика по подразбиране на хотела
    @GetMapping("/shortcuts")
    public List<ShortcutButton> getShortcuts(@RequestParam String hotelId,
                                             @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                             @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        if (!hotelRegistry.isKnown(hotelId)) {
            return List.of();
        }
        String language = hotelLanguages.resolve(hotelId, acceptLanguage);
        String defaultLanguage = hotelLanguages.of(hotelId).defaultLanguage();
        return shortcutService.getShortcutsForHotel(hotelId, chatUserResolver.resolve(hotelId, authorization) == null)
                .stream()
                .map(shortcut -> new ShortcutButton(shortcut.getShortcutId(),
                        shortcut.labelIn(language, defaultLanguage), shortcut.getCategory()))
                .toList();
    }
}
