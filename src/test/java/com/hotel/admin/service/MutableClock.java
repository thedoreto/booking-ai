package com.hotel.admin.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// Часовник, който тестовете местят напред (изтичане на токена, прозорецът на грешните опити)
class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-09-28T08:00:00Z");

    void advance(Duration duration) {
        now = now.plus(duration);
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
