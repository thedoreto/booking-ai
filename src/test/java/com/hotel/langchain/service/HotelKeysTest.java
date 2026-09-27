package com.hotel.langchain.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotelKeysTest {

    private static final KeyPair SEVEN_STARS = keyPair();
    private static final KeyPair ROBBERS = keyPair();

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final MutableClock clock = new MutableClock();
    private final HotelKeys hotelKeys = new HotelKeys(mongoTemplate, clock);

    @Test
    void readsPemAndSingleLineBase64() {
        hotels(hotel("seven_stars", pem(SEVEN_STARS)), hotel("40_robbers", base64(ROBBERS)));

        assertThat(hotelKeys.publicKey("seven_stars")).contains(SEVEN_STARS.getPublic());
        assertThat(hotelKeys.publicKey("40_robbers")).contains(ROBBERS.getPublic());
    }

    @Test
    void invalidOrMissingKeyIsSkippedWithoutBreakingOtherHotels() {
        hotels(hotel("seven_stars", pem(SEVEN_STARS)), hotel("broken", "not a key"), new Document("_id", "no_field"));

        assertThat(hotelKeys.publicKey("seven_stars")).contains(SEVEN_STARS.getPublic());
        assertThat(hotelKeys.publicKey("broken")).isEmpty();
        assertThat(hotelKeys.publicKey("no_field")).isEmpty();
    }

    @Test
    void knownKeysAreReloadedEveryFiveMinutes() {
        hotels(hotel("seven_stars", pem(SEVEN_STARS)));
        hotelKeys.publicKey("seven_stars");

        // Сменен ключ в Mongo
        hotels(hotel("seven_stars", pem(ROBBERS)));
        clock.advance(Duration.ofMinutes(4));
        assertThat(hotelKeys.publicKey("seven_stars")).contains(SEVEN_STARS.getPublic());

        clock.advance(Duration.ofMinutes(1));
        assertThat(hotelKeys.publicKey("seven_stars")).contains(ROBBERS.getPublic());
        verify(mongoTemplate, times(2)).findAll(Document.class, "hotels");
    }

    @Test
    void hotelWithoutKeyAsksMongoAtMostOncePerMinute() {
        hotels(hotel("seven_stars", pem(SEVEN_STARS)));

        for (int i = 0; i < 100; i++) {
            assertThat(hotelKeys.publicKey("40_robbers")).isEmpty();
        }
        verify(mongoTemplate, times(1)).findAll(Document.class, "hotels");

        // Добавен ключ се вижда след минута
        hotels(hotel("seven_stars", pem(SEVEN_STARS)), hotel("40_robbers", pem(ROBBERS)));
        clock.advance(Duration.ofMinutes(1));
        assertThat(hotelKeys.publicKey("40_robbers")).contains(ROBBERS.getPublic());
    }

    @Test
    void mongoErrorKeepsPreviousKeys() {
        hotels(hotel("seven_stars", pem(SEVEN_STARS)));
        hotelKeys.publicKey("seven_stars");

        when(mongoTemplate.findAll(Document.class, "hotels")).thenThrow(new RuntimeException("Atlas down"));
        clock.advance(Duration.ofMinutes(5));

        assertThat(hotelKeys.publicKey("seven_stars")).contains(SEVEN_STARS.getPublic());
    }

    private void hotels(Document... hotels) {
        when(mongoTemplate.findAll(Document.class, "hotels")).thenReturn(List.of(hotels));
    }

    private static Document hotel(String hotelId, String publicKey) {
        return new Document("_id", hotelId).append("jwtPublicKey", publicKey);
    }

    private static String base64(KeyPair keys) {
        return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }

    private static String pem(KeyPair keys) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
