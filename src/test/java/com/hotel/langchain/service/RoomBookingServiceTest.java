package com.hotel.langchain.service;

import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.log.ChatLogEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Действията от името на потребител: гостът се отказва без заявка към бекенда,
// а за влезлия потребител през Kafka отива токенът му, не userId
class RoomBookingServiceTest {

    private static final String HOTEL = "seven_stars";
    private static final ChatUser USER = new ChatUser("user-1", "token-1");

    private final HotelBackendClient backendClient = mock(HotelBackendClient.class);
    private final RoomTypeService roomTypeService = mock(RoomTypeService.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final RoomBookingService service = new RoomBookingService(
            backendClient, roomTypeService, chatHistoryService, TestTranslations.keys());
    private final String start = LocalDate.now().plusDays(10).toString();
    private final String end = LocalDate.now().plusDays(13).toString();

    @Test
    void guestIsRejectedWithoutCallingTheBackend() throws Exception {
        assertThat(service.createBookings(HOTEL, null, "2099-10-30", "2099-11-02", List.of("r-1"), null).outcome())
                .isEqualTo(ChatLogEntry.REJECTED);
        assertThat(service.myBookings(HOTEL, null, null).outcome()).isEqualTo(ChatLogEntry.REJECTED);
        assertThat(service.cancelBooking(HOTEL, null, "b-1", null).outcome()).isEqualTo(ChatLogEntry.REJECTED);

        verify(backendClient, never()).request(anyString(), anyString(), any());
    }

    @Test
    void userActionsSendTheTokenNotUserId() throws Exception {
        when(backendClient.request(eq(HOTEL), anyString(), any())).thenReturn(List.of());

        service.myBookings(HOTEL, USER, null);
        service.cancelBooking(HOTEL, USER, "b-1", null);
        service.createBookings(HOTEL, USER, "2099-10-30", "2099-11-02", List.of("r-1"), null);

        verify(backendClient).request(HOTEL, "get_upcoming_bookings", Map.of("token", "token-1"));
        verify(backendClient).request(HOTEL, "cancel_booking", Map.of("token", "token-1", "bookingId", "b-1"));
        verify(backendClient).request(HOTEL, "create_booking", Map.of("token", "token-1", "roomIds", List.of("r-1"),
                "startDate", "2099-10-30", "endDate", "2099-11-02"));
    }

    @Test
    void expiredSessionFromBackendAsksToLogInAgain() throws Exception {
        when(backendClient.request(eq(HOTEL), eq("get_upcoming_bookings"), any()))
                .thenThrow(new HotelBackendClient.HotelBackendException("Session expired"));

        UiAction result = service.myBookings(HOTEL, USER, null);

        assertThat(result.reply()).contains("backendError.sessionExpired");
    }

    // --- Свободни стаи ---

    @Test
    void wrongDatesAreRejectedWithoutCallingTheBackend() {
        assertThat(service.findAvailableRooms(HOTEL, "утре", end, null, "bg").reply()).isEqualTo("rooms.invalidDates");
        assertThat(service.findAvailableRooms(HOTEL, null, end, null, "bg").reply()).isEqualTo("rooms.invalidDates");
        assertThat(service.findAvailableRooms(HOTEL, end, start, null, "bg").reply()).isEqualTo("rooms.checkOutBeforeCheckIn");
        assertThat(service.findAvailableRooms(HOTEL, start, start, null, "bg").reply()).isEqualTo("rooms.checkOutBeforeCheckIn");
        UiAction past = service.findAvailableRooms(HOTEL, LocalDate.now().minusDays(1).toString(), end, null, "bg");
        assertThat(past.reply()).isEqualTo("rooms.checkInInPast");
        assertThat(past.outcome()).isEqualTo(ChatLogEntry.REJECTED);

        verifyNoInteractions(backendClient);
    }

    @Test
    void freeRoomsOfTheTypeAreShownForSelection() throws Exception {
        when(roomTypeService.normalize(HOTEL, "double")).thenReturn("DOUBLE");
        when(roomTypeService.nameOf(HOTEL, "DOUBLE")).thenReturn("Двойна стая");
        List<Map<String, Object>> rooms = List.of(Map.of("id", "r-1", "roomNumber", 12));
        when(backendClient.request(HOTEL, "get_available_rooms_by_dates",
                Map.of("startDate", start, "endDate", end, "roomType", "DOUBLE"))).thenReturn(rooms);

        UiAction result = service.findAvailableRooms(HOTEL, start, end, "double", "en");

        assertThat(result.actionType()).isEqualTo(RoomBookingService.SELECT_ROOMS_ACTION);
        assertThat(result.outcome()).isEqualTo(ChatLogEntry.OK);
        assertThat(result.data()).isEqualTo(Map.of("startDate", start, "endDate", end, "rooms", rooms, "roomType", "DOUBLE"));
        // Името на типа – преведено на езика на заявката
        assertThat(result.reply()).startsWith("rooms.available").contains("rooms.ofType {type=[en] Двойна стая}");
    }

    @Test
    void noFreeRoomsIsNoResult() throws Exception {
        when(backendClient.request(eq(HOTEL), eq("get_available_rooms_by_dates"), any())).thenReturn(List.of());

        UiAction result = service.findAvailableRooms(HOTEL, start, end, null, "bg");

        assertThat(result.actionType()).isNull();
        assertThat(result.outcome()).isEqualTo(ChatLogEntry.NO_RESULT);
        assertThat(result.reply()).startsWith("rooms.noneAvailable ").contains("rooms=rooms.any");
    }

    @Test
    void searchErrorsAreLoggedByType() throws Exception {
        when(backendClient.request(eq(HOTEL), eq("get_available_rooms_by_dates"), any()))
                .thenThrow(new HotelBackendClient.HotelBackendException("Invalid room type"))
                .thenThrow(new TimeoutException());

        UiAction backendError = service.findAvailableRooms(HOTEL, start, end, null, "bg");
        assertThat(backendError.reply()).isEqualTo("rooms.searchError {error=backendError.invalidRoomType}");
        assertThat(backendError.errorType()).isEqualTo(ChatLogEntry.BACKEND_ERROR);

        UiAction timeout = service.findAvailableRooms(HOTEL, start, end, null, "bg");
        assertThat(timeout.reply()).isEqualTo("common.backendUnavailable");
        assertThat(timeout.errorType()).isEqualTo(ChatLogEntry.BACKEND_TIMEOUT);
    }

    // --- Резервация ---

    @Test
    void bookingWithoutRoomsOrWithWrongDatesIsRejected() throws Exception {
        assertThat(service.createBookings(HOTEL, USER, start, end, List.of(), "bg").reply()).isEqualTo("booking.noRoomSelected");
        assertThat(service.createBookings(HOTEL, USER, start, end, null, "bg").reply()).isEqualTo("booking.noRoomSelected");
        assertThat(service.createBookings(HOTEL, USER, "x", end, List.of("r-1"), "bg").reply()).isEqualTo("booking.invalidDates");
        assertThat(service.createBookings(HOTEL, USER, end, start, List.of("r-1"), "bg").reply()).isEqualTo("rooms.checkOutBeforeCheckIn");

        verifyNoInteractions(backendClient, chatHistoryService);
    }

    @Test
    void confirmedBookingListsTheRoomsAndTheTotalAndGoesToTheChatMemory() throws Exception {
        List<Map<String, Object>> bookings = List.of(
                Map.of("roomNumber", 12, "totalPrice", 300.0),
                Map.of("roomNumber", 15, "totalPrice", 200));
        when(backendClient.request(eq(HOTEL), eq("create_booking"), any())).thenReturn(bookings);

        UiAction result = service.createBookings(HOTEL, USER, start, end, List.of("r-12", "r-15"), "bg");

        assertThat(result.actionType()).isEqualTo(RoomBookingService.BOOKING_CONFIRMED_ACTION);
        assertThat(result.data()).isEqualTo(bookings);
        String[] lines = result.reply().split("\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).startsWith("booking.confirmed {period=common.period");
        assertThat(lines[1]).startsWith("booking.confirmedRoom").contains("number=12").contains("price=" + price(300));
        assertThat(lines[2]).startsWith("booking.confirmedRoom").contains("number=15").contains("price=" + price(200));
        assertThat(lines[3]).isEqualTo("booking.total {total=" + price(500) + "}");
        // Паметта на Gemini – на български, с датите на резервацията
        verify(chatHistoryService).record(HOTEL, USER, "Резервирай стаи №12, №15 от " + bg(start) + " до " + bg(end) + ".",
                result.reply());
    }

    @Test
    void bookingTimeoutWarnsThatItMayBeSaved() throws Exception {
        when(backendClient.request(eq(HOTEL), eq("create_booking"), any())).thenThrow(new TimeoutException());

        UiAction result = service.createBookings(HOTEL, USER, start, end, List.of("r-1"), "bg");

        assertThat(result.reply()).isEqualTo("booking.timeout");
        assertThat(result.errorType()).isEqualTo(ChatLogEntry.BACKEND_TIMEOUT);
        verifyNoInteractions(chatHistoryService);
    }

    // --- Моите резервации и отказ ---

    @Test
    void myBookingsAreShownAsCardsOrNoResult() throws Exception {
        List<Map<String, Object>> bookings = List.of(Map.of("id", "b-1"));
        when(backendClient.request(eq(HOTEL), eq("get_upcoming_bookings"), any())).thenReturn(List.of(), bookings);

        UiAction none = service.myBookings(HOTEL, USER, "bg");
        assertThat(none.reply()).isEqualTo("myBookings.none");
        assertThat(none.outcome()).isEqualTo(ChatLogEntry.NO_RESULT);

        UiAction list = service.myBookings(HOTEL, USER, "bg");
        assertThat(list.actionType()).isEqualTo(RoomBookingService.MY_BOOKINGS_ACTION);
        assertThat(list.data()).isEqualTo(Map.of("bookings", bookings));
    }

    @Test
    void cancelWithoutBookingIsRejected() throws Exception {
        assertThat(service.cancelBooking(HOTEL, USER, " ", "bg").reply()).isEqualTo("cancel.noBookingSelected");
        verifyNoInteractions(backendClient);
    }

    @Test
    void canceledBookingIsDescribedAndGoesToTheChatMemory() throws Exception {
        when(roomTypeService.nameOf(HOTEL, "DOUBLE")).thenReturn("Двойна стая");
        Map<String, Object> booking = Map.of("roomNumber", 12, "roomType", "DOUBLE", "checkInDate", start, "checkOutDate", end);
        when(backendClient.request(eq(HOTEL), eq("cancel_booking"), any())).thenReturn(booking);

        UiAction result = service.cancelBooking(HOTEL, USER, "b-1", "en");

        assertThat(result.actionType()).isEqualTo(RoomBookingService.BOOKING_CANCELED_ACTION);
        assertThat(result.data()).isEqualTo(booking);
        assertThat(result.reply()).startsWith("cancel.done {booking=cancel.bookingDescription {number=12} ([en] Двойна стая) common.period");
        verify(chatHistoryService).record(eq(HOTEL), eq(USER), org.mockito.ArgumentMatchers.startsWith(
                "Откажи резервацията cancel.bookingDescription {number=12} ([en] Двойна стая)"), eq(result.reply()));
    }

    @Test
    void backendReasonsBecomeTextsAndUnknownOnesStayAsTheyCame() {
        Texts texts = TestTranslations.keys().forRequest(HOTEL, "bg");

        assertThat(service.backendErrorText("Room not available", texts)).isEqualTo("backendError.roomNotAvailable");
        assertThat(service.backendErrorText("Invalid token", texts)).isEqualTo("backendError.loginRequired");
        assertThat(service.backendErrorText("Cancellation deadline passed", texts))
                .isEqualTo("backendError.cancellationDeadlinePassed");
        assertThat(service.backendErrorText("Something new", texts)).isEqualTo("Something new");
        assertThat(service.backendErrorText(null, texts)).isNull();
    }

    // Цената, както я форматира услугата (String.format("%.2f") – по локала на JVM)
    private static String price(double value) {
        return String.format("%.2f", value);
    }

    private static String bg(String isoDate) {
        return LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }
}
