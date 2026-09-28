package com.hotel.langchain.tools;

import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.service.HotelBackendClient;
import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import com.hotel.langchain.service.TestTranslations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HotelToolsTest {

    private static final ChatUser USER = new ChatUser("user-1", "token-1");

    private final HotelBackendClient backendClient = mock(HotelBackendClient.class);
    private final RoomTypeService roomTypeService = mock(RoomTypeService.class);
    private final RoomBookingService roomBookingService = mock(RoomBookingService.class);
    private final HotelTools tools = new HotelTools(backendClient, roomTypeService, roomBookingService, TestTranslations.keys());
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setTenant() {
        TenantContext.setHotelId("seven_stars");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // --- getAvailableRoomsByDates: винаги отваря календара, попълнен с казаните дати ---

    @Test
    void calendarIsPrefilledWithTheDatesAndTheRoomType() {
        LocalDate start = today.plusDays(10);
        LocalDate end = today.plusDays(13);
        when(roomTypeService.normalize("seven_stars", "double")).thenReturn("DOUBLE");

        Map<String, Object> prefill = openCalendar(start.toString(), end.toString(), "double");

        assertThat(prefill).isEqualTo(Map.of(
                "startDate", start.toString(), "endDate", end.toString(), "roomType", "DOUBLE"));
        assertThat(TenantContext.getUiAction().actionType()).isEqualTo(OpenDatePickerException.OPEN_DATE_PICKER_ACTION);
        assertThat(TenantContext.getUiAction().reply()).isEqualTo("chat.datePicker");
    }

    @Test
    void datesInTheOtherFormatsOfTheModelAreRead() {
        LocalDate date = today.plusDays(20);

        assertThat(openCalendar(date.format(DateTimeFormatter.ofPattern("d.M.yyyy")), null, null))
                .containsEntry("startDate", date.toString());
        assertThat(openCalendar(date.format(DateTimeFormatter.ofPattern("yyyy-M-d")), null, null))
                .containsEntry("startDate", date.toString());
        assertThat(openCalendar(date.format(DateTimeFormatter.ofPattern("d/M/yyyy")), null, null))
                .containsEntry("startDate", date.toString());
        assertThat(openCalendar(date + "T00:00:00", null, null))
                .containsEntry("startDate", date.toString());
    }

    @Test
    void dateInThePastIsMovedToTheNextYear() {
        // Моделът понякога слага миналата година, а потребителят рядко казва годината
        LocalDate lastYear = today.plusDays(5).minusYears(1);

        assertThat(openCalendar(lastYear.toString(), null, null))
                .containsEntry("startDate", lastYear.plusYears(1).toString());
    }

    @Test
    void unreadableOrTooOldDatesAreLeftOut() {
        assertThat(openCalendar("null", "утре", null)).isEmpty();
        assertThat(openCalendar(" ", null, "null")).isEmpty();
        // Повече от 3 години назад – не е грешна година, а грешна дата
        assertThat(openCalendar(today.minusYears(5).toString(), null, null)).isEmpty();
    }

    @Test
    void endDateAloneMustBeAfterToday() {
        LocalDate end = today.plusDays(3);

        assertThat(openCalendar(null, end.toString(), null)).isEqualTo(Map.of("endDate", end.toString()));
    }

    // --- getReservations и грешките на tools ---

    @Test
    void guestIsAskedToLogInWithoutCallingTheBackend() {
        assertThat(tools.getReservations()).isEqualTo("tools.reservationsLoginRequired");
        verifyNoInteractions(backendClient);
    }

    @Test
    void reservationsGoToTheModelAsJsonWithTheToken() throws Exception {
        TenantContext.setUser(USER);
        List<Map<String, Object>> reservations = List.of(Map.of("roomNumber", 12));
        when(backendClient.request("seven_stars", "get_reservations", Map.of("token", "token-1"))).thenReturn(reservations);
        when(backendClient.toJson(reservations)).thenReturn("[{\"roomNumber\":12}]");

        assertThat(tools.getReservations()).isEqualTo("[{\"roomNumber\":12}]");
        assertThat(TenantContext.getToolError()).isNull();
    }

    @Test
    void backendErrorIsExplainedAndLogged() throws Exception {
        TenantContext.setUser(USER);
        when(backendClient.request(eq("seven_stars"), eq("get_reservations"), any()))
                .thenThrow(new HotelBackendException("Session expired"));
        when(roomBookingService.backendErrorText(eq("Session expired"), any())).thenReturn("Сесията изтече");

        assertThat(tools.getReservations()).isEqualTo("myBookings.error {error=Сесията изтече}");
        assertThat(TenantContext.getToolError()).isEqualTo(ChatLogEntry.BACKEND_ERROR);
    }

    @Test
    void backendTimeoutSaysTheSystemIsUnavailable() throws Exception {
        TenantContext.setUser(USER);
        when(backendClient.request(eq("seven_stars"), eq("get_reservations"), any())).thenThrow(new TimeoutException());

        assertThat(tools.getReservations()).isEqualTo("common.backendUnavailable");
        assertThat(TenantContext.getToolError()).isEqualTo(ChatLogEntry.BACKEND_TIMEOUT);
    }

    @Test
    void unexpectedErrorIsATechnicalOne() throws Exception {
        when(backendClient.request(eq("seven_stars"), eq("get_all_rooms"), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(tools.getAllRooms()).isEqualTo("tools.roomsError {error=backendError.technical}");
        assertThat(TenantContext.getToolError()).isEqualTo(ChatLogEntry.INTERNAL);
    }

    @Test
    void withoutRoomTypesTheModelIsTold() {
        when(roomTypeService.getRoomTypes("seven_stars")).thenReturn(List.of());

        assertThat(tools.getRoomTypes()).isEqualTo("tools.roomTypesUnavailable");
    }

    // Датите, с които tool-ът е отворил календара (data на UiAction)
    @SuppressWarnings("unchecked")
    private Map<String, Object> openCalendar(String start, String end, String roomType) {
        assertThatThrownBy(() -> tools.getAvailableRoomsByDates(start, end, roomType))
                .isInstanceOf(OpenDatePickerException.class);
        return (Map<String, Object>) TenantContext.getUiAction().data();
    }
}
