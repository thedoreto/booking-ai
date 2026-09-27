package com.hotel.langchain.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Лимит на съобщенията към Gemini за всеки хотел – на минута и на ден. Пази общата квота на ключа
// (един ключ за всички хотели) и не дава на един хотел да изяде квотата на другия. Не зависи от клиента
// (IP, сесия), затова не може да се заобиколи. Броят се само текстовите съобщения в чата – бутоните не викат Gemini.
// Броячите са в паметта: при рестарт започват отначало. Хотелите са само познатите (HotelRegistry), паметта не расте.
@Service
public class GeminiBudget {

    public enum Limit { MINUTE, DAY }

    // limit – null, ако съобщението може да отиде към Gemini.
    // firstRejection – първият отказ в текущия прозорец; за да не се пише лог за всяка заявка на скрипт.
    public record Result(Limit limit, boolean firstRejection) {
        static final Result OK = new Result(null, false);

        public boolean allowed() {
            return limit == null;
        }
    }

    // Дневната квота на Gemini се нулира в полунощ тихоокеанско време
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    private final int perMinute;
    private final int perDay;
    private final Clock clock;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Autowired
    public GeminiBudget(@Value("${chat.gemini-limit.per-minute:5}") int perMinute,
                        @Value("${chat.gemini-limit.per-day:200}") int perDay) {
        this(perMinute, perDay, Clock.systemUTC());
    }

    GeminiBudget(int perMinute, int perDay, Clock clock) {
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.clock = clock;
    }

    // Записва съобщението, ако е в лимита
    public Result tryAcquire(String hotelId) {
        Instant now = clock.instant();
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        LocalDate day = LocalDate.ofInstant(now, QUOTA_ZONE);
        Result[] result = new Result[1];
        counters.compute(hotelId, (id, counter) -> {
            Counter current = counter != null ? counter : new Counter();
            result[0] = current.tryAcquire(minute, day);
            return current;
        });
        return result[0];
    }

    // Използва се само в compute() за своя хотел – не е нужна друга синхронизация
    private class Counter {
        private Instant minute;
        private int minuteCount;
        private boolean minuteRejected;
        private LocalDate day;
        private int dayCount;
        private boolean dayRejected;

        Result tryAcquire(Instant currentMinute, LocalDate currentDay) {
            if (!currentDay.equals(day)) {
                day = currentDay;
                dayCount = 0;
                dayRejected = false;
            }
            if (!currentMinute.equals(minute)) {
                minute = currentMinute;
                minuteCount = 0;
                minuteRejected = false;
            }
            if (dayCount >= perDay) {
                boolean first = !dayRejected;
                dayRejected = true;
                return new Result(Limit.DAY, first);
            }
            if (minuteCount >= perMinute) {
                boolean first = !minuteRejected;
                minuteRejected = true;
                return new Result(Limit.MINUTE, first);
            }
            minuteCount++;
            dayCount++;
            return Result.OK;
        }
    }
}
