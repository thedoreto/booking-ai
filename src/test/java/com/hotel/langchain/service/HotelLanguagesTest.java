package com.hotel.langchain.service;

import com.hotel.langchain.service.HotelLanguages.Language;
import com.hotel.langchain.service.HotelLanguages.Languages;
import com.hotel.langchain.model.HotelSettings;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotelLanguagesTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final MutableClock clock = new MutableClock();
    private final HotelLanguages hotelLanguages = new HotelLanguages(mongoTemplate, clock);

    @Test
    void readsLanguagesInOrderWithDefault() {
        hotels(hotel("seven_stars", "en", language("bg", "Български"), language("en", "English")));

        assertThat(hotelLanguages.of("seven_stars")).isEqualTo(new Languages(
                List.of(new Language("bg", "Български"), new Language("en", "English")), "en"));
    }

    @Test
    void hotelWithoutLanguagesOrRecordIsBulgarianOnly() {
        hotels(hotel("seven_stars", null));

        assertThat(hotelLanguages.of("seven_stars")).isEqualTo(HotelLanguages.BULGARIAN_ONLY);
        assertThat(hotelLanguages.of("40_robbers")).isEqualTo(HotelLanguages.BULGARIAN_ONLY);
    }

    @Test
    void missingOrUnknownDefaultIsTheFirstLanguage() {
        hotels(hotel("seven_stars", null, language("en", "English"), language("bg", "Български")),
                hotel("40_robbers", "de", language("bg", "Български"), language("en", "English")));

        assertThat(hotelLanguages.of("seven_stars").defaultLanguage()).isEqualTo("en");
        assertThat(hotelLanguages.of("40_robbers").defaultLanguage()).isEqualTo("bg");
    }

    @Test
    void entriesWithoutCodeAndDuplicatesAreSkipped() {
        hotels(hotel("seven_stars", "BG", language(" BG ", "Български"), language(null, "Нищо"),
                language("en", null), language("bg", "Пак български")));

        assertThat(hotelLanguages.of("seven_stars")).isEqualTo(new Languages(
                List.of(new Language("bg", "Български"), new Language("en", "en")), "bg"));
    }

    @Test
    void recordWithoutHotelIdIsSkippedWithoutBreakingOtherHotels() {
        hotels(hotel(null, "en", language("en", "English")),
                hotel("seven_stars", "bg", language("bg", "Български"), language("en", "English")));

        assertThat(hotelLanguages.of("seven_stars").languages()).hasSize(2);
    }

    @Test
    void resolveReturnsRequestedLanguageOnlyIfTheHotelHasIt() {
        hotels(hotel("seven_stars", "bg", language("bg", "Български"), language("en", "English")));

        assertThat(hotelLanguages.resolve("seven_stars", "en")).isEqualTo("en");
        assertThat(hotelLanguages.resolve("seven_stars", " EN ")).isEqualTo("en");
        assertThat(hotelLanguages.resolve("seven_stars", "de")).isEqualTo("bg");
        assertThat(hotelLanguages.resolve("seven_stars", null)).isEqualTo("bg");
        assertThat(hotelLanguages.resolve("40_robbers", "en")).isEqualTo("bg");
    }

    @Test
    void nameOfIsTheMenuNameOrTheCode() {
        hotels(hotel("seven_stars", "bg", language("bg", "Български"), language("en", "English")));

        assertThat(hotelLanguages.nameOf("seven_stars", "en")).isEqualTo("English");
        assertThat(hotelLanguages.nameOf("seven_stars", "de")).isEqualTo("de");
    }

    @Test
    void reloadedEveryFiveMinutesAndMongoErrorKeepsPreviousLanguages() {
        hotels(hotel("seven_stars", "bg", language("bg", "Български")));
        hotelLanguages.of("seven_stars");

        hotels(hotel("seven_stars", "bg", language("bg", "Български"), language("en", "English")));
        clock.advance(Duration.ofMinutes(4));
        assertThat(hotelLanguages.of("seven_stars").languages()).hasSize(1);

        clock.advance(Duration.ofMinutes(1));
        assertThat(hotelLanguages.of("seven_stars").languages()).hasSize(2);
        verify(mongoTemplate, times(2)).findAll(HotelSettings.class);

        when(mongoTemplate.findAll(HotelSettings.class)).thenThrow(new RuntimeException("Atlas down"));
        clock.advance(Duration.ofMinutes(5));
        assertThat(hotelLanguages.of("seven_stars").languages()).hasSize(2);
    }

    private void hotels(HotelSettings... hotels) {
        when(mongoTemplate.findAll(HotelSettings.class)).thenReturn(List.of(hotels));
    }

    // Без езици – хотел без поле languages
    private static HotelSettings hotel(String hotelId, String defaultLanguage, HotelSettings.Language... languages) {
        HotelSettings hotel = new HotelSettings();
        hotel.setHotelId(hotelId);
        hotel.setLanguages(languages.length == 0 ? null : List.of(languages));
        hotel.setDefaultLanguage(defaultLanguage);
        return hotel;
    }

    private static HotelSettings.Language language(String code, String name) {
        HotelSettings.Language language = new HotelSettings.Language();
        language.setCode(code);
        language.setName(name);
        return language;
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
