package com.hotel.langchain.controller;

import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.ChatUserResolver;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.controller.AiLangChainController.AvailableRoomsRequest;
import com.hotel.langchain.controller.AiLangChainController.CancelBookingRequest;
import com.hotel.langchain.controller.AiLangChainController.ChatRequest;
import com.hotel.langchain.controller.AiLangChainController.CreateBookingRequest;
import com.hotel.langchain.controller.AiLangChainController.Message;
import com.hotel.langchain.controller.AiLangChainController.MyBookingsRequest;
import com.hotel.langchain.controller.AiLangChainController.NewChatResponse;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.log.ChatFlow;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.log.ChatLogService;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.service.GeminiBudget;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelRegistry;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import com.hotel.langchain.service.ShortcutService;
import com.hotel.langchain.service.TestTranslations;
import com.hotel.langchain.tools.ShortcutToolRunner;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Потоците през контролера: бутони (знание, tool), отговор и грешки от Gemini, адресите без LLM и какво
// се записва в логовете (отделен запис или стъпка в ChatFlow, с flowId в data на отговора)
class AiLangChainControllerFlowTest {

    private static final String HOTEL = "seven_stars";
    private static final ChatUser USER = new ChatUser("user-1", "token-1");

    private final Assistant assistant = mock(Assistant.class);
    private final ShortcutService shortcutService = mock(ShortcutService.class);
    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final ShortcutToolRunner shortcutToolRunner = mock(ShortcutToolRunner.class);
    private final ChatLogService chatLogService = mock(ChatLogService.class);
    private final RoomBookingService roomBookingService = mock(RoomBookingService.class);
    private final HotelRegistry hotelRegistry = mock(HotelRegistry.class);
    private final GeminiBudget geminiBudget = mock(GeminiBudget.class);
    private final ChatUserResolver chatUserResolver = mock(ChatUserResolver.class);
    private final HotelLanguages hotelLanguages = mock(HotelLanguages.class);
    private final AiLangChainController controller = new AiLangChainController(assistant, shortcutService,
            knowledgeService, shortcutToolRunner, chatLogService, roomBookingService, mock(RoomTypeService.class),
            hotelRegistry, geminiBudget, chatUserResolver, hotelLanguages, TestTranslations.keys());

    {
        when(hotelRegistry.isKnown(HOTEL)).thenReturn(true);
        when(geminiBudget.tryAcquire(HOTEL)).thenReturn(new GeminiBudget.Result(null, false));
        when(hotelLanguages.of(HOTEL)).thenReturn(new HotelLanguages.Languages(
                List.of(new HotelLanguages.Language("bg", "Български")), "bg"));
        when(hotelLanguages.resolve(eq(HOTEL), any())).thenReturn("bg");
        when(chatUserResolver.resolve(HOTEL, "Bearer t")).thenReturn(USER);
    }

    // --- Бутони ---

    @Test
    void knowledgeButtonAnswersWithTheTextsWithoutGemini() {
        List<ObjectId> ids = List.of(new ObjectId(), new ObjectId());
        button("parking", knowledge(ids));
        when(knowledgeService.textsByIds(eq(HOTEL), eq(ids), any())).thenReturn(List.of("Паркингът е безплатен.", "Има и зарядна станция."));

        NewChatResponse response = controller.chat(button(HOTEL, "parking", null), null, null);

        assertThat(response).isEqualTo(new NewChatResponse("Паркингът е безплатен.\n\nИма и зарядна станция.", null));
        assertThat(loggedEntry().outcome()).isEqualTo(ChatLogEntry.OK);
        verifyNoInteractions(assistant);
    }

    @Test
    void missingHiddenOrEmptyButtonIsNotFound() {
        Shortcut hidden = button("staff", knowledge(List.of(new ObjectId())));
        Shortcut.Guest guest = new Shortcut.Guest();
        guest.setIsActive(false);
        hidden.setGuest(guest);
        button("empty", null);
        button("no_texts", knowledge(List.of(new ObjectId())));
        when(knowledgeService.textsByIds(eq(HOTEL), any(), any())).thenReturn(List.of());

        // Скритият от госта бутон не се изпълнява и при директна заявка
        assertThat(controller.chat(button(HOTEL, "staff", null), null, null).reply()).isEqualTo("chat.notFound");
        assertThat(controller.chat(button(HOTEL, "unknown", null), null, null).reply()).isEqualTo("chat.notFound");
        assertThat(controller.chat(button(HOTEL, "empty", null), null, null).reply()).isEqualTo("chat.notFound");
        assertThat(controller.chat(button(HOTEL, "no_texts", null), null, null).reply()).isEqualTo("chat.notFound");
    }

    @Test
    void calendarButtonStartsANewBookingFlow() {
        button("new_booking", tool("getAvailableRoomsByDates"));
        when(shortcutToolRunner.run("getAvailableRoomsByDates")).thenAnswer(call -> {
            TenantContext.requestUiAction(new UiAction(OpenDatePickerException.OPEN_DATE_PICKER_ACTION, "Изберете дати", Map.of()));
            return Optional.of("");
        });

        NewChatResponse response = controller.chat(button(HOTEL, "new_booking", null), null, null);

        assertThat(response.actionType()).isEqualTo(OpenDatePickerException.OPEN_DATE_PICKER_ACTION);
        String flowId = flowIdOf(response);
        verify(chatLogService).logStep(eq(HOTEL), eq(flowId), eq(ChatFlow.NEW_BOOKING), eq(ChatFlow.STARTED_BY_BUTTON),
                eq(ChatFlow.DATE_PICKER), any());
    }

    @Test
    void calendarContinuesTheBookingFlowOfTheUi() {
        String flowId = UUID.randomUUID().toString();
        button("new_booking", tool("getAvailableRoomsByDates"));
        when(shortcutToolRunner.run("getAvailableRoomsByDates")).thenAnswer(call -> {
            TenantContext.requestUiAction(new UiAction(OpenDatePickerException.OPEN_DATE_PICKER_ACTION, "Изберете дати", null));
            return Optional.of("");
        });

        NewChatResponse response = controller.chat(button(HOTEL, "new_booking", flowId), null, null);

        assertThat(flowIdOf(response)).isEqualTo(flowId);
    }

    @Test
    void myBookingsButtonStartsACancelFlowAndKeepsTheList() {
        button("my_bookings", tool("showMyBookings"));
        List<Map<String, Object>> bookings = List.of(Map.of("id", "b-1"));
        when(shortcutToolRunner.run("showMyBookings")).thenAnswer(call -> {
            TenantContext.requestUiAction(new UiAction(RoomBookingService.MY_BOOKINGS_ACTION, "Вашите резервации",
                    Map.of("bookings", bookings)));
            return Optional.of("Вашите резервации");
        });

        NewChatResponse response = controller.chat(button(HOTEL, "my_bookings", null), "Bearer t", null);

        assertThat(response.actionType()).isEqualTo(RoomBookingService.MY_BOOKINGS_ACTION);
        assertThat(((Map<?, ?>) response.data()).get("bookings")).isEqualTo(bookings);
        verify(chatLogService).logStep(eq(HOTEL), eq(flowIdOf(response)), eq(ChatFlow.CANCEL_BOOKING),
                eq(ChatFlow.STARTED_BY_BUTTON), eq(ChatFlow.BOOKINGS_SHOWN), any());
    }

    @Test
    void toolButtonWithTextAnswersWithItAndLogsTheToolError() {
        button("room_types", tool("getRoomTypes"));
        when(shortcutToolRunner.run("getRoomTypes")).thenAnswer(call -> {
            TenantContext.reportToolError(ChatLogEntry.BACKEND_TIMEOUT);
            return Optional.of("common.backendUnavailable");
        });

        NewChatResponse response = controller.chat(button(HOTEL, "room_types", null), null, null);

        assertThat(response).isEqualTo(new NewChatResponse("common.backendUnavailable", null));
        assertThat(loggedEntry().outcome()).isEqualTo(ChatLogEntry.ERROR);
    }

    @Test
    void toolButtonWithoutAnswerIsNotFound() {
        button("unknown_tool", tool("noSuchTool"));
        when(shortcutToolRunner.run("noSuchTool")).thenReturn(Optional.empty());

        assertThat(controller.chat(button(HOTEL, "unknown_tool", null), null, null).reply()).isEqualTo("chat.notFound");
        assertThat(loggedEntry().outcome()).isEqualTo(ChatLogEntry.NO_RESULT);
    }

    @Test
    void failingButtonGivesATechnicalError() {
        when(shortcutService.findActiveShortcut(HOTEL, "broken")).thenThrow(new RuntimeException("Atlas down"));

        assertThat(controller.chat(button(HOTEL, "broken", null), null, null).reply()).isEqualTo("common.technicalError");
        assertThat(loggedEntry().outcome()).isEqualTo(ChatLogEntry.ERROR);
    }

    // --- Въпрос в чата (Gemini) ---

    @Test
    void geminiAnswerGoesToTheUiAndTheLog() {
        when(assistant.chat(any(), eq(HOTEL), any(), any(), any(), any(), eq("Има ли паркинг?"))).thenReturn("Да, безплатен.");

        assertThat(controller.chat(question("Има ли паркинг?"), null, null))
                .isEqualTo(new NewChatResponse("Да, безплатен.", null));
        assertThat(loggedEntry().outcome()).isEqualTo(ChatLogEntry.OK);
    }

    @Test
    void uiActionOfAToolReplacesTheTextOfTheModel() {
        when(assistant.chat(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            TenantContext.requestUiAction(new UiAction(OpenDatePickerException.OPEN_DATE_PICKER_ACTION, "Изберете дати",
                    Map.of("startDate", "2099-10-30")));
            return "текст, който не трябва да стигне до UI";
        });

        NewChatResponse response = controller.chat(question("Искам стая за 30 октомври"), null, null);

        assertThat(response.reply()).isEqualTo("Изберете дати");
        assertThat(((Map<?, ?>) response.data()).get("startDate")).isEqualTo("2099-10-30");
        verify(chatLogService).logStep(eq(HOTEL), eq(flowIdOf(response)), eq(ChatFlow.NEW_BOOKING),
                eq(ChatFlow.STARTED_BY_CHAT), eq(ChatFlow.DATE_PICKER), any());
    }

    @Test
    void geminiErrorsGetFriendlyMessages() {
        when(assistant.chat(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("wrapped", new RuntimeException("HTTP error (429): RESOURCE_EXHAUSTED")))
                .thenThrow(new RuntimeException("HTTP error (503): UNAVAILABLE"))
                .thenThrow(new IllegalStateException("boom"));

        assertThat(controller.chat(question("1"), null, null).reply()).isEqualTo("chat.geminiQuotaExhausted");
        assertThat(controller.chat(question("2"), null, null).reply()).isEqualTo("chat.geminiOverloaded");
        assertThat(controller.chat(question("3"), null, null).reply()).isEqualTo("chat.geminiError");

        ArgumentCaptor<ChatLogEntry> entries = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService, times(3)).log(eq(HOTEL), entries.capture());
        assertThat(entries.getAllValues()).extracting(ChatLogEntry::outcome).containsOnly(ChatLogEntry.ERROR);
    }

    // --- Адресите без LLM ---

    @Test
    void availableRoomsIsAStepOfTheBookingFlow() {
        String flowId = UUID.randomUUID().toString();
        when(roomBookingService.findAvailableRooms(HOTEL, "2099-10-30", "2099-11-02", null, "bg"))
                .thenReturn(new UiAction(RoomBookingService.SELECT_ROOMS_ACTION, "Свободни стаи",
                        Map.of("rooms", List.of(Map.of("id", "r-1")))));

        NewChatResponse response = controller.availableRooms(
                new AvailableRoomsRequest(HOTEL, "2099-10-30", "2099-11-02", null, flowId), null, null);

        assertThat(response.actionType()).isEqualTo(RoomBookingService.SELECT_ROOMS_ACTION);
        assertThat(flowIdOf(response)).isEqualTo(flowId);
        verify(chatLogService).logStep(eq(HOTEL), eq(flowId), eq(ChatFlow.NEW_BOOKING), eq(ChatFlow.STARTED_BY_BUTTON),
                eq(ChatFlow.ROOMS_SHOWN), any());
    }

    @Test
    void searchStatusFollowsTheOutcome() {
        when(roomBookingService.findAvailableRooms(eq(HOTEL), eq("2099-10-30"), any(), any(), any()))
                .thenReturn(new UiAction(null, "Няма стаи", null, ChatLogEntry.NO_RESULT, null));
        when(roomBookingService.findAvailableRooms(eq(HOTEL), eq("2099-10-31"), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        controller.availableRooms(new AvailableRoomsRequest(HOTEL, "2099-10-30", "2099-11-02", null, null), null, null);
        NewChatResponse failed = controller.availableRooms(
                new AvailableRoomsRequest(HOTEL, "2099-10-31", "2099-11-02", null, null), null, null);

        assertThat(failed.reply()).isEqualTo("rooms.searchFailed");
        // Без flowId от UI – ново действие, и то пак се връща на UI
        assertThat(flowIdOf(failed)).isNotBlank();
        verify(chatLogService).logStep(eq(HOTEL), anyString(), eq(ChatFlow.NEW_BOOKING), anyString(), eq(ChatFlow.NO_ROOMS), any());
        verify(chatLogService).logStep(eq(HOTEL), anyString(), eq(ChatFlow.NEW_BOOKING), anyString(), eq(ChatFlow.ERROR), any());
    }

    @Test
    void bookingIsTheLastStepOfTheBookingFlow() {
        String flowId = UUID.randomUUID().toString();
        List<Map<String, Object>> bookings = List.of(Map.of("id", "b-1", "nights", 3, "totalPrice", 300.0));
        when(roomBookingService.createBookings(HOTEL, USER, "2099-10-30", "2099-11-02", List.of("r-1"), "bg"))
                .thenReturn(new UiAction(RoomBookingService.BOOKING_CONFIRMED_ACTION, "Резервирано", bookings));
        when(roomBookingService.createBookings(HOTEL, USER, "2099-10-30", "2099-11-02", List.of("r-2"), "bg"))
                .thenThrow(new IllegalStateException("boom"));

        NewChatResponse booked = controller.createBooking(
                new CreateBookingRequest(HOTEL, "2099-10-30", "2099-11-02", List.of("r-1"), flowId), "Bearer t", null);
        NewChatResponse failed = controller.createBooking(
                new CreateBookingRequest(HOTEL, "2099-10-30", "2099-11-02", List.of("r-2"), flowId), "Bearer t", null);

        assertThat(booked.actionType()).isEqualTo(RoomBookingService.BOOKING_CONFIRMED_ACTION);
        // Списъкът с резервации остава в data, без flowId
        assertThat(booked.data()).isEqualTo(bookings);
        assertThat(failed.reply()).isEqualTo("booking.failed");
        verify(chatLogService).logStep(eq(HOTEL), eq(flowId), eq(ChatFlow.NEW_BOOKING), anyString(), eq(ChatFlow.BOOKED), any());
        verify(chatLogService).logStep(eq(HOTEL), eq(flowId), eq(ChatFlow.NEW_BOOKING), anyString(),
                eq(ChatFlow.BOOKING_FAILED), any());
    }

    @Test
    void myBookingsListStartsACancelFlowButEmptyListIsAPlainLog() {
        when(roomBookingService.myBookings(HOTEL, USER, "bg"))
                .thenReturn(new UiAction(RoomBookingService.MY_BOOKINGS_ACTION, "Вашите резервации",
                        Map.of("bookings", List.of(Map.of("id", "b-1")))))
                .thenReturn(new UiAction(null, "Нямате резервации", null, ChatLogEntry.NO_RESULT, null));

        NewChatResponse list = controller.myBookings(new MyBookingsRequest(HOTEL), "Bearer t", null);
        NewChatResponse none = controller.myBookings(new MyBookingsRequest(HOTEL), "Bearer t", null);

        verify(chatLogService).logStep(eq(HOTEL), eq(flowIdOf(list)), eq(ChatFlow.CANCEL_BOOKING), anyString(),
                eq(ChatFlow.BOOKINGS_SHOWN), any());
        assertThat(none).isEqualTo(new NewChatResponse("Нямате резервации", null));
        assertThat(loggedEntry().outcome()).isEqualTo(ChatLogEntry.NO_RESULT);
    }

    @Test
    void myBookingsFailureIsATechnicalError() {
        when(roomBookingService.myBookings(HOTEL, USER, "bg")).thenThrow(new IllegalStateException("boom"));

        assertThat(controller.myBookings(new MyBookingsRequest(HOTEL), "Bearer t", null).reply())
                .isEqualTo("myBookings.failed");
        assertThat(loggedEntry().outcome()).isEqualTo(ChatLogEntry.ERROR);
    }

    @Test
    void cancelIsAStepOfTheCancelFlow() {
        String flowId = UUID.randomUUID().toString();
        Map<String, Object> booking = Map.of("roomNumber", 12);
        when(roomBookingService.cancelBooking(HOTEL, USER, "b-1", "bg"))
                .thenReturn(new UiAction(RoomBookingService.BOOKING_CANCELED_ACTION, "Отказана", booking));
        when(roomBookingService.cancelBooking(HOTEL, USER, "b-2", "bg")).thenThrow(new IllegalStateException("boom"));

        NewChatResponse canceled = controller.cancelBooking(new CancelBookingRequest(HOTEL, "b-1", flowId), "Bearer t", null);
        NewChatResponse failed = controller.cancelBooking(new CancelBookingRequest(HOTEL, "b-2", flowId), "Bearer t", null);

        // Отказът е последна стъпка – UI не получава flowId
        assertThat(canceled).isEqualTo(new NewChatResponse("Отказана", RoomBookingService.BOOKING_CANCELED_ACTION, booking));
        assertThat(failed).isEqualTo(new NewChatResponse("cancel.failed", null));
        verify(chatLogService).logStep(eq(HOTEL), eq(flowId), eq(ChatFlow.CANCEL_BOOKING), anyString(), eq(ChatFlow.CANCELED), any());
        verify(chatLogService).logStep(eq(HOTEL), eq(flowId), eq(ChatFlow.CANCEL_BOOKING), anyString(),
                eq(ChatFlow.CANCEL_FAILED), any());
        verify(chatLogService, never()).log(anyString(), any());
    }

    // --- Помощни ---

    private Shortcut button(String shortcutId, Shortcut.Action action) {
        Shortcut shortcut = new Shortcut();
        shortcut.setShortcutId(shortcutId);
        shortcut.setLabel(Map.of("bg", shortcutId));
        shortcut.setAction(action);
        when(shortcutService.findActiveShortcut(HOTEL, shortcutId)).thenReturn(shortcut);
        return shortcut;
    }

    private static Shortcut.Action knowledge(List<ObjectId> ids) {
        Shortcut.Action action = new Shortcut.Action();
        action.setType(Shortcut.Action.KNOWLEDGE);
        action.setKnowledgeIds(ids);
        return action;
    }

    private static Shortcut.Action tool(String name) {
        Shortcut.Action action = new Shortcut.Action();
        action.setType(Shortcut.Action.TOOL);
        action.setTool(name);
        return action;
    }

    private static ChatRequest button(String hotelId, String shortcutId, String flowId) {
        return new ChatRequest(hotelId, List.of(), shortcutId, flowId, null);
    }

    private static ChatRequest question(String text) {
        return new ChatRequest(HOTEL, List.of(new Message("user", text)), null, null, null);
    }

    // flowId в data на отговора – валиден UUID
    private static String flowIdOf(NewChatResponse response) {
        String flowId = String.valueOf(((Map<?, ?>) response.data()).get("flowId"));
        assertThat(UUID.fromString(flowId).toString()).isEqualTo(flowId);
        return flowId;
    }

    // Единственият отделен запис в logs_ (не стъпка в действие)
    private ChatLogEntry loggedEntry() {
        ArgumentCaptor<ChatLogEntry> entry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService).log(eq(HOTEL), entry.capture());
        return entry.getValue();
    }
}
