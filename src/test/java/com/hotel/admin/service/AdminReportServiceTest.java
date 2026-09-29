package com.hotel.admin.service;

import com.hotel.admin.service.AdminReportService.InvalidPeriodException;
import com.hotel.admin.service.AdminReportService.GeminiCount;
import com.hotel.admin.service.AdminReportService.GeminiReport;
import com.hotel.langchain.log.ChatReports;
import com.hotel.langchain.model.GeminiUsage;
import com.hotel.langchain.repository.ChatLogRepository;
import com.hotel.langchain.repository.GeminiUsageRepository;
import com.hotel.langchain.service.GeminiBudget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    private final ChatLogRepository repository = mock(ChatLogRepository.class);
    private final GeminiUsageRepository usageRepository = mock(GeminiUsageRepository.class);
    private final GeminiBudget budget = mock(GeminiBudget.class);
    private final AdminReportService service = new AdminReportService(repository, usageRepository, budget,
            Clock.fixed(NOW, ZoneOffset.UTC));

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
        assertThatThrownBy(() -> service.geminiReport("40_robbers", 0)).isInstanceOf(InvalidPeriodException.class);
        assertThatThrownBy(() -> service.geminiReport("40_robbers", 181)).isInstanceOf(InvalidPeriodException.class);
        verifyNoInteractions(repository, usageRepository);
    }

    @Test
    void geminiReportSumsTheDailyCountersBySourceAndByDay() {
        // 10:00 UTC на 29.09 е 03:00 на 29.09 в Калифорния – 7 дни заедно с днешния започват от 23.09
        when(usageRepository.findFrom("40_robbers", "2026-09-23")).thenReturn(List.of(
                usage("2026-09-28", GeminiUsage.tokens(GeminiUsage.CHAT, "gemini-test", 3, 1, 300, 30)),
                usage("2026-09-28", GeminiUsage.characters(GeminiUsage.CHAT_EMBEDDING, "gemini-embedding-001", 2, 0, 40)),
                usage("2026-09-29", GeminiUsage.tokens(GeminiUsage.ADMIN_TRANSLATION, "gemini-test", 1, 0, 100, 50)),
                usage("2026-09-29", GeminiUsage.tokens(GeminiUsage.CHAT, "gemini-test", 2, 0, 200, 20))));
        ChatReports.GeminiShare share = new ChatReports.GeminiShare(10, 4, 0, 1);
        when(repository.geminiShare("40_robbers", Instant.parse("2026-09-22T10:00:00Z"))).thenReturn(share);
        when(budget.perMinute()).thenReturn(5);
        when(budget.perDay()).thenReturn(200);

        GeminiReport report = service.geminiReport("40_robbers", 7);

        assertThat(report.total()).isEqualTo(new GeminiCount(null, null, 8, 1, 600L, 100L, 40L));
        assertThat(report.bySource()).containsExactly(
                new GeminiCount(GeminiUsage.CHAT, "gemini-test", 5, 1, 500L, 50L, null),
                new GeminiCount(GeminiUsage.CHAT_EMBEDDING, "gemini-embedding-001", 2, 0, null, null, 40L),
                new GeminiCount(GeminiUsage.ADMIN_TRANSLATION, "gemini-test", 1, 0, 100L, 50L, null));
        assertThat(report.byDay()).containsExactly(
                new GeminiCount("2026-09-29", null, 3, 0, 300L, 70L, null),
                new GeminiCount("2026-09-28", null, 5, 1, 300L, 30L, 40L));
        assertThat(report.share()).isEqualTo(share);
        assertThat(report.limitPerMinute()).isEqualTo(5);
        assertThat(report.limitPerDay()).isEqualTo(200);
    }

    @Test
    void geminiReportWithoutCountersIsZero() {
        GeminiReport report = service.geminiReport("40_robbers", 30);

        assertThat(report.total()).isEqualTo(new GeminiCount(null, null, 0, 0, null, null, null));
        assertThat(report.bySource()).isEmpty();
        assertThat(report.byDay()).isEmpty();
    }

    private static GeminiUsage usage(String day, GeminiUsage usage) {
        usage.setDay(day);
        return usage;
    }
}
