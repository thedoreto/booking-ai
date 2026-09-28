package com.hotel.langchain.service;

import org.junit.jupiter.api.Test;
import com.hotel.knowledge.repository.KnowledgeRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HotelRegistryTest {

    private final KnowledgeRepository knowledgeRepository = mock(KnowledgeRepository.class);
    private final MutableClock clock = new MutableClock();
    private final HotelRegistry registry = new HotelRegistry(knowledgeRepository, clock);

    @Test
    void hotelWithKnowledgeIsKnown() {
        when(knowledgeRepository.findHotelIds()).thenReturn(Set.of("seven_stars"));

        assertThat(registry.isKnown("seven_stars")).isTrue();
        assertThat(registry.isKnown("fake")).isFalse();
        assertThat(registry.isKnown("other")).isFalse();
    }

    @Test
    void unsafeIdsAreRejectedWithoutAskingMongo() {
        assertThat(registry.isKnown(null)).isFalse();
        assertThat(registry.isKnown("")).isFalse();
        assertThat(registry.isKnown("seven.stars")).isFalse();
        assertThat(registry.isKnown("a b")).isFalse();
        assertThat(registry.isKnown("x".repeat(65))).isFalse();
        verifyNoInteractions(knowledgeRepository);
    }

    @Test
    void unknownHotelsAskMongoAtMostOncePerMinute() {
        when(knowledgeRepository.findHotelIds()).thenReturn(Set.of("seven_stars"));

        for (int i = 0; i < 100; i++) {
            assertThat(registry.isKnown("fake_" + i)).isFalse();
        }
        verify(knowledgeRepository, times(1)).findHotelIds();

        // Нов хотел се вижда след минута
        when(knowledgeRepository.findHotelIds()).thenReturn(Set.of("seven_stars", "new_hotel"));
        assertThat(registry.isKnown("new_hotel")).isFalse();
        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.isKnown("new_hotel")).isTrue();
        verify(knowledgeRepository, times(2)).findHotelIds();
    }

    @Test
    void knownHotelDoesNotAskMongoAgain() {
        when(knowledgeRepository.findHotelIds()).thenReturn(Set.of("seven_stars"));

        registry.isKnown("seven_stars");
        clock.advance(Duration.ofHours(1));
        registry.isKnown("seven_stars");

        verify(knowledgeRepository, times(1)).findHotelIds();
    }

    @Test
    void mongoErrorKeepsPreviousList() {
        when(knowledgeRepository.findHotelIds()).thenReturn(Set.of("seven_stars"));
        registry.isKnown("seven_stars");

        when(knowledgeRepository.findHotelIds()).thenThrow(new RuntimeException("Atlas down"));
        clock.advance(Duration.ofMinutes(1));

        assertThat(registry.isKnown("fake")).isFalse();
        assertThat(registry.isKnown("seven_stars")).isTrue();
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-27T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
