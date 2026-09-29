package com.hotel.admin.service;

import com.hotel.langchain.log.ChatReports;
import com.hotel.langchain.repository.ChatLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

// Отчетите на хотела в админ панела – от логовете (logs_<hotelId>), без Gemini. Период – последните days дни
// (най-много колкото се пазят логовете).
@Service
public class AdminReportService {

    public static final int MAX_DAYS = 180;
    // Търсенията без свободни стаи – най-много толкова реда
    private static final int NO_ROOMS_LIMIT = 20;

    public record Report(int days, ChatReports.Summary summary, ChatReports.BookingFunnel funnel,
                         List<ChatReports.NoRoomsSearch> noRooms, List<ChatReports.ButtonUsage> buttons) {}

    public static class InvalidPeriodException extends RuntimeException {
        public InvalidPeriodException() {
            super("INVALID_PERIOD");
        }
    }

    private final ChatLogRepository chatLogRepository;
    private final Clock clock;

    @Autowired
    public AdminReportService(ChatLogRepository chatLogRepository) {
        this(chatLogRepository, Clock.systemUTC());
    }

    AdminReportService(ChatLogRepository chatLogRepository, Clock clock) {
        this.chatLogRepository = chatLogRepository;
        this.clock = clock;
    }

    public Report report(String hotelId, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new InvalidPeriodException();
        }
        Instant from = clock.instant().minus(Duration.ofDays(days));
        return new Report(days,
                chatLogRepository.summary(hotelId, from),
                chatLogRepository.bookingFunnel(hotelId, from),
                chatLogRepository.noRoomsSearches(hotelId, from, NO_ROOMS_LIMIT),
                chatLogRepository.buttonUsage(hotelId, from));
    }
}
