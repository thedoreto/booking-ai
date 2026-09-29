package com.hotel.langchain.controller;

import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.ChatUserResolver;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.controller.AiLangChainController.AvailableRoomsRequest;
import com.hotel.langchain.controller.AiLangChainController.CancelBookingRequest;
import com.hotel.langchain.controller.AiLangChainController.ChatSettings;
import com.hotel.langchain.controller.AiLangChainController.ChatRequest;
import com.hotel.langchain.controller.AiLangChainController.CreateBookingRequest;
import com.hotel.langchain.controller.AiLangChainController.Message;
import com.hotel.langchain.controller.AiLangChainController.MyBookingsRequest;
import com.hotel.langchain.controller.AiLangChainController.NewChatResponse;
import com.hotel.langchain.controller.AiLangChainController.ShortcutButton;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

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
    private static final String UNKNOWN_HOTEL_REPLY = "chat.unknownHotel";
    private static final String MISSING_HOTEL_REPLY = "chat.missingHotel";

    private final Assistant assistant = mock(Assistant.class);
    private final ShortcutService shortcutService = mock(ShortcutService.class);
    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final ShortcutToolRunner shortcutToolRunner = mock(ShortcutToolRunner.class);
    private final ChatLogService chatLogService = mock(ChatLogService.class);
    private final RoomBookingService roomBookingService = mock(RoomBookingService.class);
    private final RoomTypeService roomTypeService = mock(RoomTypeService.class);
    private final HotelRegistry hotelRegistry = mock(HotelRegistry.class);
    private final GeminiBudget geminiBudget = mock(GeminiBudget.class);
    private final ChatUserResolver chatUserResolver = mock(ChatUserResolver.class);
    private final HotelLanguages hotelLanguages = mock(HotelLanguages.class);
    private final AiLangChainController controller = new AiLangChainController(assistant, shortcutService,
            knowledgeService, shortcutToolRunner, chatLogService, roomBookingService, roomTypeService, hotelRegistry,
            geminiBudget, chatUserResolver, hotelLanguages, TestTranslations.keys());

    {
        when(hotelRegistry.isKnown(KNOWN)).thenReturn(true);
        when(geminiBudget.tryAcquire(KNOWN)).thenReturn(new GeminiBudget.Result(null, false));
        when(hotelLanguages.of(KNOWN)).thenReturn(new HotelLanguages.Languages(
                List.of(new HotelLanguages.Language("bg", "Български"), new HotelLanguages.Language("en", "English")), "bg"));
    }

    @Test
    void unknownHotelIsRejectedOnEveryAddress() {
        assertThat(controller.chat(chatRequest(UNKNOWN, null), null, null).reply()).isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.chat(chatRequest(UNKNOWN, "some-button"), null, null).reply()).isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.availableRooms(
                new AvailableRoomsRequest(UNKNOWN, "2099-10-30", "2099-11-02", null, null), null, null).reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.createBooking(
                new CreateBookingRequest(UNKNOWN, "2099-10-30", "2099-11-02", List.of("r-1"), null), "Bearer t", null).reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.myBookings(new MyBookingsRequest(UNKNOWN), "Bearer t", null).reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.cancelBooking(new CancelBookingRequest(UNKNOWN, "b-1", null), "Bearer t", null).reply())
                .isEqualTo(UNKNOWN_HOTEL_REPLY);
        assertThat(controller.getShortcuts(UNKNOWN, null, null)).isEmpty();
        assertThat(controller.getRoomTypes(UNKNOWN, null)).isEmpty();
        assertThat(controller.getChatSettings(UNKNOWN, "en")).isEqualTo(new ChatSettings(List.of(), null, Map.of()));

        verifyNothingElseCalled();
    }

    @Test
    void missingHotelIsRejectedWithoutAskingTheRegistry() {
        assertThat(controller.chat(chatRequest(" ", null), null, null).reply()).isEqualTo(MISSING_HOTEL_REPLY);
        assertThat(controller.availableRooms(null, null, null).reply()).isEqualTo(MISSING_HOTEL_REPLY);
        assertThat(controller.cancelBooking(new CancelBookingRequest(null, "b-1", null), null, null).reply())
                .isEqualTo(MISSING_HOTEL_REPLY);

        verifyNoInteractions(hotelRegistry);
        verifyNothingElseCalled();
    }

    @Test
    void knownHotelGoesThrough() {
        Shortcut button = new Shortcut();
        button.setShortcutId("parking");
        button.setLabel(Map.of("bg", "Паркинг"));
        when(shortcutService.getShortcutsForHotel(KNOWN, true)).thenReturn(List.of(button));
        when(roomTypeService.getRoomTypes(KNOWN)).thenReturn(List.of(new RoomTypeService.RoomType("DOUBLE", "Двойна")));
        when(roomBookingService.cancelBooking(eq(KNOWN), any(), eq("b-1"), any()))
                .thenReturn(new UiAction("BOOKING_CANCELLED", "Резервацията е отказана.", null));

        assertThat(controller.getShortcuts(KNOWN, null, null)).containsExactly(new ShortcutButton("parking", "Паркинг", null));
        when(hotelLanguages.resolve(KNOWN, "en")).thenReturn("en");
        assertThat(controller.getRoomTypes(KNOWN, "en")).containsExactly(new RoomTypeService.RoomType("DOUBLE", "[en] Двойна"));
        assertThat(controller.cancelBooking(new CancelBookingRequest(KNOWN, "b-1", null), "Bearer t", null).reply())
                .isEqualTo("Резервацията е отказана.");

        verify(chatLogService).logStep(eq(KNOWN), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void chatSettingsHaveTheHotelLanguagesAndTheResolvedOne() {
        List<HotelLanguages.Language> languages =
                List.of(new HotelLanguages.Language("bg", "Български"), new HotelLanguages.Language("en", "English"));
        when(hotelLanguages.of(KNOWN)).thenReturn(new HotelLanguages.Languages(languages, "bg"));
        when(hotelLanguages.resolve(KNOWN, "en")).thenReturn("en");

        assertThat(controller.getChatSettings(KNOWN, "en")).isEqualTo(new ChatSettings(languages, "en", Map.of()));
    }

    @Test
    void tooLongMessageIsRejectedWithoutGemini() {
        NewChatResponse response = controller.chat(chatRequest(KNOWN, null, "а".repeat(501)), null, null);

        assertThat(response.reply()).isEqualTo("chat.messageTooLong {max=500}");
        verifyNoInteractions(assistant);
        ArgumentCaptor<ChatLogEntry> logEntry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService).log(eq(KNOWN), logEntry.capture());
        assertThat(logEntry.getValue().outcome()).isEqualTo(ChatLogEntry.REJECTED);
    }

    @Test
    void messageAtTheLimitGoesToGemini() {
        String text = "а".repeat(500);
        when(assistant.chat(any(), any(), any(), any(), any(), any(), eq(text))).thenReturn("Отговор");

        assertThat(controller.chat(chatRequest(KNOWN, null, text), null, null).reply()).isEqualTo("Отговор");
    }

    @Test
    void knowledgeScoresOfTheQuestionGoToTheLog() {
        // RAG (HotelContentRetriever) записва оценките по време на извикването към Gemini
        when(assistant.chat(any(), any(), any(), any(), any(), any(), eq("Има ли паркинг?"))).thenAnswer(invocation -> {
            TenantContext.setKnowledgeScores(List.of(0.83, 0.64));
            return "Да.";
        });

        controller.chat(chatRequest(KNOWN, null, "Има ли паркинг?"), null, null);

        ArgumentCaptor<ChatLogEntry> logEntry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService).log(eq(KNOWN), logEntry.capture());
        assertThat(logEntry.getValue().detail("knowledgeScores")).isEqualTo(List.of(0.83, 0.64));
    }

    @Test
    void noInfoMarkerIsRemovedAndTheQuestionIsLoggedWithoutAnswer() {
        when(assistant.chat(any(), any(), any(), any(), any(), any(), eq("Имате ли басейн?")))
                .thenReturn(Assistant.NO_INFO_MARKER + " Нямам информация за басейн.");

        NewChatResponse response = controller.chat(chatRequest(KNOWN, null, "Имате ли басейн?"), null, null);

        assertThat(response.reply()).isEqualTo("Нямам информация за басейн.");
        ArgumentCaptor<ChatLogEntry> logEntry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService).log(eq(KNOWN), logEntry.capture());
        assertThat(logEntry.getValue().outcome()).isEqualTo(ChatLogEntry.NO_RESULT);
    }

    @Test
    void answerWithoutTheMarkerIsOk() {
        when(assistant.chat(any(), any(), any(), any(), any(), any(), eq("Има ли паркинг?"))).thenReturn("Да, безплатен.");

        assertThat(controller.chat(chatRequest(KNOWN, null, "Има ли паркинг?"), null, null).reply()).isEqualTo("Да, безплатен.");
        ArgumentCaptor<ChatLogEntry> logEntry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService).log(eq(KNOWN), logEntry.capture());
        assertThat(logEntry.getValue().outcome()).isEqualTo(ChatLogEntry.OK);
    }

    @Test
    void thePromptAsksForTheSameMarkerThatTheControllerRemoves() throws Exception {
        String prompt = String.join("\n", Assistant.class
                .getMethod("chat", String.class, String.class, String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(dev.langchain4j.service.SystemMessage.class).value());

        assertThat(prompt).contains(Assistant.NO_INFO_MARKER);
    }

    @Test
    void geminiGetsTheNameOfTheLanguageFromAcceptLanguage() {
        when(hotelLanguages.resolve(KNOWN, "en")).thenReturn("en");
        when(hotelLanguages.nameOf(KNOWN, "en")).thenReturn("English");
        when(assistant.chat(any(), any(), any(), any(), any(), eq("English"), eq("Hi"))).thenReturn("Hello");

        assertThat(controller.chat(chatRequest(KNOWN, null, "Hi"), null, "en").reply()).isEqualTo("Hello");
    }

    @Test
    void hotelOverTheGeminiLimitGetsNoGemini() {
        when(geminiBudget.tryAcquire(KNOWN))
                .thenReturn(new GeminiBudget.Result(GeminiBudget.Limit.MINUTE, true))
                .thenReturn(new GeminiBudget.Result(GeminiBudget.Limit.MINUTE, false))
                .thenReturn(new GeminiBudget.Result(GeminiBudget.Limit.DAY, true));

        assertThat(controller.chat(chatRequest(KNOWN, null), null, null).reply())
                .isEqualTo("chat.hotelLimitMinute");
        assertThat(controller.chat(chatRequest(KNOWN, null), null, null).reply())
                .isEqualTo("chat.hotelLimitMinute");
        assertThat(controller.chat(chatRequest(KNOWN, null), null, null).reply()).isEqualTo("chat.hotelLimitDay");

        verifyNoInteractions(assistant);
        // Само първите откази в прозореца се записват в логовете
        ArgumentCaptor<ChatLogEntry> logEntry = ArgumentCaptor.forClass(ChatLogEntry.class);
        verify(chatLogService, times(2)).log(eq(KNOWN), logEntry.capture());
        assertThat(logEntry.getAllValues()).allMatch(entry -> ChatLogEntry.REJECTED.equals(entry.outcome()));
    }

    @Test
    void buttonsDoNotCountTowardsTheGeminiLimit() {
        controller.chat(chatRequest(KNOWN, "parking"), null, null);

        verifyNoInteractions(geminiBudget);
    }

    @Test
    void userComesFromTheResolverForTheRequestedHotel() {
        ChatUser user = new ChatUser("user-1", "t");
        when(chatUserResolver.resolve(KNOWN, "Bearer t")).thenReturn(user);
        when(roomBookingService.cancelBooking(KNOWN, user, "b-1", null))
                .thenReturn(new UiAction("BOOKING_CANCELLED", "Резервацията е отказана.", null));

        assertThat(controller.cancelBooking(new CancelBookingRequest(KNOWN, "b-1", null), "Bearer t", null).reply())
                .isEqualTo("Резервацията е отказана.");
        verify(roomBookingService).cancelBooking(KNOWN, user, "b-1", null);
    }

    @Test
    void addressesWithoutGeminiAnswerInTheResolvedLanguage() {
        when(hotelLanguages.resolve(KNOWN, "en")).thenReturn("en");
        when(roomBookingService.findAvailableRooms(KNOWN, "2099-10-30", "2099-11-02", null, "en"))
                .thenReturn(new UiAction(null, "No rooms", null));

        assertThat(controller.availableRooms(
                new AvailableRoomsRequest(KNOWN, "2099-10-30", "2099-11-02", null, null), null, "en").reply())
                .isEqualTo("No rooms");
        verify(roomBookingService).findAvailableRooms(KNOWN, "2099-10-30", "2099-11-02", null, "en");
    }

    @Test
    void buttonLabelIsInTheChosenLanguageOrTheHotelDefault() {
        Shortcut translated = new Shortcut();
        translated.setShortcutId("parking");
        translated.setLabel(Map.of("bg", "Паркинг", "en", "Parking"));
        Shortcut bulgarianOnly = new Shortcut();
        bulgarianOnly.setShortcutId("access");
        bulgarianOnly.setLabel(Map.of("bg", "Достъп"));
        when(shortcutService.getShortcutsForHotel(KNOWN, true)).thenReturn(List.of(translated, bulgarianOnly));
        when(hotelLanguages.resolve(KNOWN, "en")).thenReturn("en");

        assertThat(controller.getShortcuts(KNOWN, null, "en")).extracting(ShortcutButton::label)
                .containsExactly("Parking", "Достъп");
    }

    @Test
    void unverifiedTokenGetsTheGuestButtons() {
        // Резолверът не е приел токена (подправен, изтекъл, друг хотел) – бутоните са като за гост
        controller.getShortcuts(KNOWN, "Bearer forged", null);

        verify(shortcutService).getShortcutsForHotel(KNOWN, true);
    }

    @Test
    void emptyMessageDoesNotGoToGemini() {
        assertThat(controller.chat(chatRequest(KNOWN, null, "  "), null, null).reply()).isEqualTo("chat.missingMessages");
        assertThat(controller.chat(chatRequest(KNOWN, null, null), null, null).reply()).isEqualTo("chat.missingMessages");

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
                chatLogService, roomBookingService, roomTypeService, geminiBudget, chatUserResolver, hotelLanguages);
    }
}
