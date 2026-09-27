package com.hotel.langchain.service;

import com.hotel.langchain.service.GeminiBudget.Limit;
import com.hotel.langchain.service.GeminiBudget.Result;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiBudgetTest {

    private static final String HOTEL = "seven_stars";

    // 10:00:00 тихоокеанско време (лятно, UTC-7)
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T17:00:00Z"));
    private final GeminiBudget budget = new GeminiBudget(3, 5, clock);

    @Test
    void minuteLimitRejectsAndResetsNextMinute() {
        acquire(3);

        Result first = budget.tryAcquire(HOTEL);
        Result second = budget.tryAcquire(HOTEL);
        assertThat(first.limit()).isEqualTo(Limit.MINUTE);
        assertThat(first.firstRejection()).isTrue();
        assertThat(second.limit()).isEqualTo(Limit.MINUTE);
        assertThat(second.firstRejection()).isFalse();

        clock.advance(Duration.ofMinutes(1));
        assertThat(budget.tryAcquire(HOTEL).allowed()).isTrue();
    }

    @Test
    void dayLimitRejectsUntilMidnightPacificTime() {
        acquire(3);
        clock.advance(Duration.ofMinutes(1));
        acquire(2);

        Result first = budget.tryAcquire(HOTEL);
        assertThat(first.limit()).isEqualTo(Limit.DAY);
        assertThat(first.firstRejection()).isTrue();

        // На следващата минута – пак дневният лимит, не минутният
        clock.advance(Duration.ofMinutes(1));
        Result later = budget.tryAcquire(HOTEL);
        assertThat(later.limit()).isEqualTo(Limit.DAY);
        assertThat(later.firstRejection()).isFalse();

        // 23:59 тихоокеанско време – още същият ден
        clock.set(Instant.parse("2026-09-28T06:59:00Z"));
        assertThat(budget.tryAcquire(HOTEL).limit()).isEqualTo(Limit.DAY);

        // Полунощ тихоокеанско време – нов ден
        clock.set(Instant.parse("2026-09-28T07:00:00Z"));
        assertThat(budget.tryAcquire(HOTEL).allowed()).isTrue();
    }

    @Test
    void rejectedMessagesAreNotCounted() {
        acquire(3);
        budget.tryAcquire(HOTEL); // отказано – не се брои в дневния лимит

        clock.advance(Duration.ofMinutes(1));
        acquire(2); // 3 + 2 = 5 = дневният лимит
        assertThat(budget.tryAcquire(HOTEL).limit()).isEqualTo(Limit.DAY);
    }

    @Test
    void hotelsHaveSeparateLimits() {
        acquire(3);

        assertThat(budget.tryAcquire(HOTEL).allowed()).isFalse();
        assertThat(budget.tryAcquire("40_robbers").allowed()).isTrue();
    }

    private void acquire(int times) {
        for (int i = 0; i < times; i++) {
            assertThat(budget.tryAcquire(HOTEL).allowed()).isTrue();
        }
    }

    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
