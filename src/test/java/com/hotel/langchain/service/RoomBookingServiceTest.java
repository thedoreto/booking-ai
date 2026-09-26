package com.hotel.langchain.service;

import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.log.ChatLogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Действията от името на потребител: гостът се отказва без заявка към бекенда,
// а за влезлия потребител през Kafka отива токенът му, не userId
class RoomBookingServiceTest {

    private static final String HOTEL = "seven_stars";
    private static final ChatUser USER = new ChatUser("user-1", "token-1");

    private final HotelBackendClient backendClient = mock(HotelBackendClient.class);
    private final RoomBookingService service = new RoomBookingService(
            backendClient, mock(RoomTypeService.class), mock(ChatHistoryService.class));

    @Test
    void guestIsRejectedWithoutCallingTheBackend() throws Exception {
        assertThat(service.createBookings(HOTEL, null, "2099-10-30", "2099-11-02", List.of("r-1")).outcome())
                .isEqualTo(ChatLogEntry.REJECTED);
        assertThat(service.myBookings(HOTEL, null).outcome()).isEqualTo(ChatLogEntry.REJECTED);
        assertThat(service.cancelBooking(HOTEL, null, "b-1").outcome()).isEqualTo(ChatLogEntry.REJECTED);

        verify(backendClient, never()).request(anyString(), anyString(), any());
    }

    @Test
    void userActionsSendTheTokenNotUserId() throws Exception {
        when(backendClient.request(eq(HOTEL), anyString(), any())).thenReturn(List.of());

        service.myBookings(HOTEL, USER);
        service.cancelBooking(HOTEL, USER, "b-1");
        service.createBookings(HOTEL, USER, "2099-10-30", "2099-11-02", List.of("r-1"));

        verify(backendClient).request(HOTEL, "get_upcoming_bookings", Map.of("token", "token-1"));
        verify(backendClient).request(HOTEL, "cancel_booking", Map.of("token", "token-1", "bookingId", "b-1"));
        verify(backendClient).request(HOTEL, "create_booking", Map.of("token", "token-1", "roomIds", List.of("r-1"),
                "startDate", "2099-10-30", "endDate", "2099-11-02"));
    }

    @Test
    void expiredSessionFromBackendAsksToLogInAgain() throws Exception {
        when(backendClient.request(eq(HOTEL), eq("get_upcoming_bookings"), any()))
                .thenThrow(new HotelBackendClient.HotelBackendException("Session expired"));

        UiAction result = service.myBookings(HOTEL, USER);

        assertThat(result.reply()).contains("сесията ви е изтекла");
    }
}
