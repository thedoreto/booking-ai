package com.hotel.admin.service;

import com.hotel.langchain.log.ChatReports;
import com.hotel.langchain.model.GeminiUsage;
import com.hotel.langchain.repository.ChatLogRepository;
import com.hotel.langchain.repository.GeminiUsageRepository;
import com.hotel.langchain.service.GeminiBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// Отчетите на хотела в админ панела – от логовете (logs_<hotelId>) и броячите на Gemini (gemini_usage_<hotelId>),
// без да вика Gemini. Период – последните days дни (най-много колкото се пазят логовете).
@Service
public class AdminReportService {

    public static final int MAX_DAYS = 180;
    // Търсенията без свободни стаи – най-много толкова реда
    private static final int NO_ROOMS_LIMIT = 20;

    public record Report(int days, ChatReports.Summary summary, ChatReports.BookingFunnel funnel,
                         List<ChatReports.NoRoomsSearch> noRooms, List<ChatReports.ButtonUsage> buttons) {}

    // Отчетът „Gemini“: total – всичко за периода; bySource – по източник и модел; byDay – по дни (най-новият първи,
    // само дните със заявки; денят е по тихоокеанско време, като квотата на Gemini); share – колко от действията в чата
    // са викали Gemini и колко пъти е стигнат лимитът; limitPerMinute / limitPerDay – лимитът на хотела (GeminiBudget).
    // Без цена – само заявки и токени (Gemini не връща цена).
    public record GeminiReport(int days, GeminiCount total, List<GeminiCount> bySource, List<GeminiCount> byDay,
                               ChatReports.GeminiShare share, int limitPerMinute, int limitPerDay) {}

    // Сбор от броячите. key – source (в bySource, с model) или ден (в byDay); null в total.
    // Токените са null, ако няма чат модел; characters – null, ако няма embedding.
    public record GeminiCount(String key, String model, long calls, long errors, Long inputTokens, Long outputTokens,
                              Long characters) {}

    // Редът на източниците в таблицата
    private static final List<String> SOURCE_ORDER = List.of(GeminiUsage.CHAT, GeminiUsage.CHAT_EMBEDDING,
            GeminiUsage.ADMIN_EMBEDDING, GeminiUsage.ADMIN_TRANSLATION);

    public static class InvalidPeriodException extends RuntimeException {
        public InvalidPeriodException() {
            super("INVALID_PERIOD");
        }
    }

    private final ChatLogRepository chatLogRepository;
    private final GeminiUsageRepository geminiUsageRepository;
    private final GeminiBudget geminiBudget;
    private final Clock clock;

    @Autowired
    public AdminReportService(ChatLogRepository chatLogRepository, GeminiUsageRepository geminiUsageRepository,
                              GeminiBudget geminiBudget) {
        this(chatLogRepository, geminiUsageRepository, geminiBudget, Clock.systemUTC());
    }

    AdminReportService(ChatLogRepository chatLogRepository, GeminiUsageRepository geminiUsageRepository,
                       GeminiBudget geminiBudget, Clock clock) {
        this.chatLogRepository = chatLogRepository;
        this.geminiUsageRepository = geminiUsageRepository;
        this.geminiBudget = geminiBudget;
        this.clock = clock;
    }

    public Report report(String hotelId, int days) {
        checkPeriod(days);
        Instant from = clock.instant().minus(Duration.ofDays(days));
        return new Report(days,
                chatLogRepository.summary(hotelId, from),
                chatLogRepository.bookingFunnel(hotelId, from),
                chatLogRepository.noRoomsSearches(hotelId, from, NO_ROOMS_LIMIT),
                chatLogRepository.buttonUsage(hotelId, from));
    }

    public GeminiReport geminiReport(String hotelId, int days) {
        checkPeriod(days);
        // Последните days дни заедно с днешния
        LocalDate today = LocalDate.now(clock.withZone(GeminiBudget.QUOTA_ZONE));
        List<GeminiUsage> usage = geminiUsageRepository.findFrom(hotelId, today.minusDays(days - 1L).toString());
        List<GeminiCount> bySource = sum(usage, GeminiUsage::getSource, true).stream()
                .sorted(Comparator.comparingInt((GeminiCount c) -> sourceOrder(c.key()))
                        .thenComparing(GeminiCount::model, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<GeminiCount> byDay = sum(usage, GeminiUsage::getDay, false).stream()
                .sorted(Comparator.comparing(GeminiCount::key).reversed())
                .toList();
        GeminiCount total = sum(usage, u -> null, false).stream().findFirst()
                .orElse(new GeminiCount(null, null, 0, 0, null, null, null));
        Instant from = clock.instant().minus(Duration.ofDays(days));
        return new GeminiReport(days, total, bySource, byDay, chatLogRepository.geminiShare(hotelId, from),
                geminiBudget.perMinute(), geminiBudget.perDay());
    }

    private static void checkPeriod(int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new InvalidPeriodException();
        }
    }

    // Сборове по ключ (и по модел, ако byModel), в реда на първата поява
    private static List<GeminiCount> sum(List<GeminiUsage> usage, Function<GeminiUsage, String> key, boolean byModel) {
        Map<List<String>, GeminiCount> sums = new LinkedHashMap<>();
        for (GeminiUsage u : usage) {
            String model = byModel ? u.getModel() : null;
            GeminiCount count = new GeminiCount(key.apply(u), model, value(u.getCalls()), value(u.getErrors()),
                    u.getInputTokens(), u.getOutputTokens(), u.getCharacters());
            sums.merge(Arrays.asList(key.apply(u), model), count, AdminReportService::plus);
        }
        return new ArrayList<>(sums.values());
    }

    private static GeminiCount plus(GeminiCount a, GeminiCount b) {
        return new GeminiCount(a.key(), a.model(), a.calls() + b.calls(), a.errors() + b.errors(),
                add(a.inputTokens(), b.inputTokens()), add(a.outputTokens(), b.outputTokens()),
                add(a.characters(), b.characters()));
    }

    private static int sourceOrder(String source) {
        int index = SOURCE_ORDER.indexOf(source);
        return index < 0 ? SOURCE_ORDER.size() : index;
    }

    private static long value(Integer n) {
        return n == null ? 0 : n;
    }

    // null + null остава null – полето не се отнася за тези заявки
    private static Long add(Long a, Long b) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        return a + b;
    }
}
