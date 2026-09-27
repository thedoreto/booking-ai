package com.hotel.langchain.controller;

import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.controller.AiLangChainController.AvailableRoomsRequest;
import com.hotel.langchain.controller.AiLangChainController.CancelBookingRequest;
import com.hotel.langchain.controller.AiLangChainController.ChatRequest;
import com.hotel.langchain.controller.AiLangChainController.CreateBookingRequest;
import com.hotel.langchain.controller.AiLangChainController.Message;
import com.hotel.langchain.controller.AiLangChainController.MyBookingsRequest;
import com.hotel.langchain.controller.AiLangChainController.NewChatResponse;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.log.ChatLogService;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.service.GeminiBudget;
import com.hotel.langchain.service.HotelRegistry;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import com.hotel.langchain.service.ShortcutService;
import com.hotel.langchain.tools.ShortcutToolRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Проверките на входа: непознат hotelId се отказва на всеки адрес, преди логовете (logs_<hotelId>), Gemini и Kafka;
// твърде дълго или празно съобщение и хотел над лимита (GeminiBudget) не стигат до Gemini
class AiLangChainControllerTest {

    private static final String KNOWN = "seven_stars";
    private static final String UNKNOWN = "fake_hotel";
    private static final String UNKNOWN_HOTEL_REPLY = "Хотелът не е намерен.";
    private static final String MISSING_HOTEL_REPLY = "Липсва хотел.";

    private final Assistant assistant = mock(Assistant.class);
    private final ShortcutService shortcutService = mock(ShortcutService.class);
    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final ShortcutToolRunner shortcutToolRunner = mock(ShortcutToolRunner.class);
    private final ChatLogService chatLogService = mock(ChatLogService.class);
    private final RoomBookingService roomBookingService = mock(RoomBookingService.class);
    private final RoomTypeService roomTypeService = mock(RoomTypeService.class);
    private final HotelRegistry hotelRegistry = mock(HotelRegistry.class);
    private final GeminiBudget geminiBudget = mock(GeminiBudget.class);
    private final AiLangChainController controller = new AiLangChainController(assistant, shortcutService,
            knowledgeService, shortcutToolRunner, chatLogService, roomBookingService, roomTypeService, hotelRegistry,
            geminiBudget);

    {
        when(hotelRegistry.isKnown(KNOWN)).thenReturn(true);
        when(geminiBudget.tryAcquire(KNOWN)).thenReturn(new GeminiBudget.Result(null, false));
    }

    @Test
    void unknownHotelIsRejectedOnEveryAddress() {
        assertThat(controller.chat(chatRequest(UNKNOWN, null), null).reply()).isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.chat(chatRequest(UNKNOWN, "some-button"), null).reply()).isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.availableRooms(
                new AvailableRoomsRequest(UNKNOWN, "2099-10-30", "2099-11-02", null, null), null).reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.createBooking(
                new CreateBookingRequest(UNKNOWN, "2099-10-30", "2099-11-02", List.of("r-1"), null), "Bearer t").reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.myBookings(new MyBookingsRequest(UNKNOWN), "Bearer t").reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.cancelBooking(new CancelBookingRequest(UNKNOWN, "b-1", null), "Bearer t").reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.getShortcuts(UNKNOWN, null)).isEmpty();
        assertThat(controller.getRoomTypes(UNKNOWN)).isEmpty();

        verifyNothingElseCalled();
    }

    @Test
    void missingHotelIsRejectedWithoutAskingTheRegistry() {
        assertThat(controller.chat(chatRequest(" ", null), null).reply()).isEqualTo(MISSING_HOTEL_REPLY);
        assertThat(controller.availableRooms(null, null).reply()).isEqualTo(MISSING_HOTEL_REPLY);
        assertThat(controller.cancelBooking(new CancelBookingRequest(null, "b-1", null), null).reply())
                .isEqualTo(MISSING_HOTEL_REPLY);

        verifyNoInteractions(hotelRegistry);
        verifyNothingElseCalled();
    }

    @Test
    void knownHotelGoesThrough() {
        Shortcut button = new Shortcut();
        when(shortcutService.getShortcutsForHotel(KNOWN, true)).thenReturn(List.of(button));
        when(roomTypeService.getRoomTypes(KNOWN)).thenReturn(List.of(new RoomTypeService.RoomType("DOUBLE", "Двойна")));
        when(roomBookingService.cancelBooking(eq(KNOWN), any(), eq("b-1")))
                .thenReturn(new UiAction("BOOKING_CANCELLED", "Резервацията е отказана.", null));

        assertThat(controller.getShortcuts(KNOWN, null)).containsExactly(button);
        assertThat(controller.getRoomTypes(KNOWN)).hasSize(1);
        assertThat(controller.cancelBooking(new CancelBookingRequest(KNOWN, "b-1", null), "Bearer t").reply())
                .isEqualTo("Резервацията е отказана.");

        verify(chatLogService).logStep(eq(KNOWN), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void tooLongMessageIsRejectedWithoutGemini() {
        NewChatResponse response = controller.chat(chatRequest(KNOWN, null, "а".repeat(501)), null);

        assertThat(response.reply()).isEqualTo("Съобщението е твърде дълго. Моля, съкратете го до 500 знака.");
        verifyNoInteractions(assistant);
        ArgumentCaptor<ChatLogEntry> logEntry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService).log(eq(KNOWN), logEntry.capture());
        assertThat(logEntry.getValue().outcome()).isEqualTo(ChatLogEntry.REJECTED);
    }

    @Test
    void messageAtTheLimitGoesToGemini() {
        String text = "а".repeat(500);
        when(assistant.chat(any(), any(), any(), any(), any(), eq(text))).thenReturn("Отговор");

        assertThat(controller.chat(chatRequest(KNOWN, null, text), null).reply()).isEqualTo("Отговор");
    }

    @Test
    void hotelOverTheGeminiLimitGetsNoGemini() {
        when(geminiBudget.tryAcquire(KNOWN))
                .thenReturn(new GeminiBudget.Result(GeminiBudget.Limit.MINUTE, true))
                .thenReturn(new GeminiBudget.Result(GeminiBudget.Limit.MINUTE, false))
                .thenReturn(new GeminiBudget.Result(GeminiBudget.Limit.DAY, true));

        assertThat(controller.chat(chatRequest(KNOWN, null), null).reply())
                .isEqualTo("Асистентът е зает в момента. Моля, опитайте отново след минута.");
        assertThat(controller.chat(chatRequest(KNOWN, null), null).reply())
                .isEqualTo("Асистентът е зает в момента. Моля, опитайте отново след минута.");
        assertThat(controller.chat(chatRequest(KNOWN, null), null).reply()).startsWith("Асистентът не може да отговаря");

        verifyNoInteractions(assistant);
        // Само първите откази в прозореца се записват в логовете
        ArgumentCaptor<ChatLogEntry> logEntry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService, times(2)).log(eq(KNOWN), logEntry.capture());
        assertThat(logEntry.getAllValues()).allMatch(entry -> ChatLogEntry.REJECTED.equals(entry.outcome()));
    }

    @Test
    void buttonsDoNotCountTowardsTheGeminiLimit() {
        controller.chat(chatRequest(KNOWN, "parking"), null);

        verifyNoInteractions(geminiBudget);
    }

    @Test
    void emptyMessageDoesNotGoToGemini() {
        assertThat(controller.chat(chatRequest(KNOWN, null, "  "), null).reply()).isEqualTo("Липсват съобщения.");
        assertThat(controller.chat(chatRequest(KNOWN, null, null), null).reply()).isEqualTo("Липсват съобщения.");

        verifyNoInteractions(assistant, chatLogService);
    }

    private static ChatRequest chatRequest(String hotelId, String shortcutId) {
        return chatRequest(hotelId, shortcutId, "Има ли паркинг?");
    }

    private static ChatRequest chatRequest(String hotelId, String shortcutId, String text) {
        return new ChatRequest(hotelId, List.of(new Message("user", text)), shortcutId, null, null);
    }

    private void verifyNothingElseCalled() {
        verifyNoInteractions(assistant, shortcutService, knowledgeService, shortcutToolRunner,
                chatLogService, roomBookingService, roomTypeService, geminiBudget);
    }
}
