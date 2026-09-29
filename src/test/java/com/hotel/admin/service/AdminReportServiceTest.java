package com.hotel.admin.service;

import com.hotel.admin.service.AdminReportService.InvalidPeriodException;
import com.hotel.langchain.repository.ChatLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    private final ChatLogRepository repository = mock(ChatLogRepository.class);
    private final AdminReportService service = new AdminReportService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void periodIsTheLastDaysDays() {
        service.report("40_robbers", 7);

        Instant from = Instant.parse("2026-09-22T10:00:00Z");
        verify(repository).summary("40_robbers", from);
        verify(repository).bookingFunnel("40_robbers", from);
        verify(repository).noRoomsSearches("40_robbers", from, 20);
        verify(repository).buttonUsage("40_robbers", from);
    }

    @Test
    void periodMustBeBetweenOneAnd180Days() {
        assertThatThrownBy(() -> service.report("40_robbers", 0)).isInstanceOf(InvalidPeriodException.class);
        assertThatThrownBy(() -> service.report("40_robbers", 181)).isInstanceOf(InvalidPeriodException.class);
        verifyNoInteractions(repository);
    }
}
