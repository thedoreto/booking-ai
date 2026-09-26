package com.hotel.langchain.tools;

import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.service.HotelBackendClient;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortcutToolRunnerTest {

    private final RoomBookingService roomBookingService = mock(RoomBookingService.class);
    private final ShortcutToolRunner runner = new ShortcutToolRunner(new HotelTools(
            mock(HotelBackendClient.class), mock(RoomTypeService.class), roomBookingService));

    @BeforeEach
    void setTenant() {
        TenantContext.setHotelId("seven_stars");
        TenantContext.setUserId("user-1");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void toolThatOpensTheCalendarLeavesUiAction() {
        assertThat(runner.run("getAvailableRoomsByDates")).contains("");

        TenantContext.UiAction action = TenantContext.getUiAction();
        assertThat(action.actionType()).isEqualTo(OpenDatePickerException.OPEN_DATE_PICKER_ACTION);
        assertThat(action.data()).isEqualTo(Map.of());
    }

    @Test
    void toolRunsWithTheCurrentUser() {
        TenantContext.UiAction bookings = new TenantContext.UiAction("MY_BOOKINGS", "Вашите резервации", Map.of());
        when(roomBookingService.myBookings("seven_stars", "user-1")).thenReturn(bookings);

        assertThat(runner.run("showMyBookings")).contains("Вашите резервации");
        assertThat(TenantContext.getUiAction()).isEqualTo(bookings);
    }

    @Test
    void unknownToolGivesNothing() {
        assertThat(runner.run("noSuchTool")).isEmpty();
        assertThat(runner.run(null)).isEmpty();
    }
}
