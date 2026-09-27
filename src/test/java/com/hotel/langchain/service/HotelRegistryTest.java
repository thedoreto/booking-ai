package com.hotel.langchain.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

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

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final MutableClock clock = new MutableClock();
    private final HotelRegistry registry = new HotelRegistry(mongoTemplate, clock);

    @Test
    void hotelWithKnowledgeCollectionIsKnown() {
        when(mongoTemplate.getCollectionNames())
                .thenReturn(Set.of("knowledge_seven_stars", "shortcuts_seven_stars", "logs_fake", "shortcuts_other"));

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
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void unknownHotelsAskMongoAtMostOncePerMinute() {
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("knowledge_seven_stars"));

        for (int i = 0; i < 100; i++) {
            assertThat(registry.isKnown("fake_" + i)).isFalse();
        }
        verify(mongoTemplate, times(1)).getCollectionNames();

        // Нов хотел се вижда след минута
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("knowledge_seven_stars", "knowledge_new_hotel"));
        assertThat(registry.isKnown("new_hotel")).isFalse();
        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.isKnown("new_hotel")).isTrue();
        verify(mongoTemplate, times(2)).getCollectionNames();
    }

    @Test
    void knownHotelDoesNotAskMongoAgain() {
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("knowledge_seven_stars"));

        registry.isKnown("seven_stars");
        clock.advance(Duration.ofHours(1));
        registry.isKnown("seven_stars");

        verify(mongoTemplate, times(1)).getCollectionNames();
    }

    @Test
    void mongoErrorKeepsPreviousList() {
        when(mongoTemplate.getCollectionNames()).thenReturn(Set.of("knowledge_seven_stars"));
        registry.isKnown("seven_stars");

        when(mongoTemplate.getCollectionNames()).thenThrow(new RuntimeException("Atlas down"));
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
