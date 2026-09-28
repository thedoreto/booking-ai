package com.hotel.langchain.service;

import com.hotel.langchain.model.Translation;
import com.hotel.langchain.repository.TranslationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationServiceTest {

    private static final String HOTEL = "seven_stars";

    private final TranslationRepository repository = mock(TranslationRepository.class);
    private final HotelLanguages hotelLanguages = mock(HotelLanguages.class);
    private final MutableClock clock = new MutableClock();
    private final TranslationService translations = new TranslationService(repository, hotelLanguages, clock);

    @BeforeEach
    void setUp() {
        // Хотел без собствени преводи и с български по подразбиране, освен ако тестът не каже друго
        when(repository.findByHotelId(anyString())).thenReturn(List.of());
        when(repository.findCommon()).thenReturn(List.of());
        defaultLanguage(HOTEL, "bg");
    }

    @Test
    void messageFillsParamsInTheRequestedLanguage() {
        common(translation("rooms.available", Map.of(
                "bg", "Свободни {rooms} за периода {period}.",
                "en", "Available {rooms} {period}.")));

        assertThat(translations.forRequest(HOTEL, "en")
                .message("rooms.available", Map.of("rooms", "rooms", "period", "from 01.10.2026 to 03.10.2026")))
                .isEqualTo("Available rooms from 01.10.2026 to 03.10.2026.");
        assertThat(translations.forRequest(HOTEL, "bg")
                .message("rooms.available", Map.of("rooms", "стаи", "period", "от 01.10.2026 до 03.10.2026")))
                .isEqualTo("Свободни стаи за периода от 01.10.2026 до 03.10.2026.");
    }

    @Test
    void hotelMessageWinsOverTheCommonOne() {
        common(translation("booking.timeout", Map.of("bg", "Общо", "en", "Common")));
        hotel(HOTEL, translation("booking.timeout", Map.of("en", "Hotel")));

        assertThat(translations.forRequest(HOTEL, "en").message("booking.timeout")).isEqualTo("Hotel");
        // На български хотелът няма свой текст – общият
        assertThat(translations.forRequest(HOTEL, "bg").message("booking.timeout")).isEqualTo("Общо");
        // Другият хотел вижда само общия
        defaultLanguage("40_robbers", "bg");
        assertThat(translations.forRequest("40_robbers", "en").message("booking.timeout")).isEqualTo("Common");
    }

    @Test
    void messageFallsBackToTheHotelDefaultLanguageThenBulgarianThenTheKey() {
        defaultLanguage(HOTEL, "en");
        common(translation("chat.notFound", Map.of("bg", "Не е намерена.", "en", "Not found.")),
                translation("chat.onlyBg", Map.of("bg", "Само български.")));

        assertThat(translations.forRequest(HOTEL, "de").message("chat.notFound")).isEqualTo("Not found.");
        assertThat(translations.forRequest(HOTEL, "de").message("chat.onlyBg")).isEqualTo("Само български.");
        assertThat(translations.forRequest(HOTEL, null).message("chat.notFound")).isEqualTo("Not found.");
        assertThat(translations.forRequest(HOTEL, "en").message("chat.unknown")).isEqualTo("chat.unknown");
    }

    @Test
    void requestedLanguageInTheCommonWinsOverTheDefaultLanguageAtTheHotel() {
        defaultLanguage(HOTEL, "en");
        common(translation("ui.send", Map.of("de", "Senden")));
        hotel(HOTEL, translation("ui.send", Map.of("en", "Send it")));

        assertThat(translations.forRequest(HOTEL, "de").message("ui.send")).isEqualTo("Senden");
    }

    @Test
    void withoutHotelOnlyTheCommonTranslationsAreRead() {
        common(translation("chat.unknownHotel", Map.of("bg", "Непознат хотел.", "en", "Unknown hotel.")));

        assertThat(translations.forRequest(null, "en").message("chat.unknownHotel")).isEqualTo("Unknown hotel.");
        verify(repository, never()).findByHotelId(anyString());
        verify(hotelLanguages, never()).of(anyString());
    }

    @Test
    void withPrefixJoinsBothLayersWithTheHotelFirst() {
        common(translation("ui.send", Map.of("bg", "Изпрати", "en", "Send")),
                translation("ui.cancel", Map.of("bg", "Отказ")),
                translation("booking.error", Map.of("bg", "Грешка", "en", "Error")),
                translation(null, Map.of("bg", "Апартамент", "en", "Apartment")));
        hotel(HOTEL, translation("ui.send", Map.of("en", "Go")),
                translation("ui.title", Map.of("en", "Seven stars")));

        assertThat(translations.forRequest(HOTEL, "en").withPrefix("ui."))
                .isEqualTo(Map.of("ui.send", "Go", "ui.cancel", "Отказ", "ui.title", "Seven stars"));
    }

    @Test
    void translateFindsTheTextInAnyLanguage() {
        common(translation(null, Map.of("bg", "Единична стая", "en", "Single room", "de", "Einzelzimmer")));

        assertThat(translations.forRequest(HOTEL, "en").translate("Единична стая")).isEqualTo("Single room");
        assertThat(translations.forRequest(HOTEL, "bg").translate("Einzelzimmer")).isEqualTo("Единична стая");
        assertThat(translations.forRequest(HOTEL, "de").translate(" Single room ")).isEqualTo("Einzelzimmer");
    }

    @Test
    void translateLooksAtTheHotelFirstThenTheCommon() {
        common(translation(null, Map.of("bg", "Единична стая", "en", "Single room", "de", "Einzelzimmer")));
        hotel(HOTEL, translation(null, Map.of("bg", "Единична стая", "en", "Standard single")));

        assertThat(translations.forRequest(HOTEL, "en").translate("Единична стая")).isEqualTo("Standard single");
        // Хотелът няма немски – от общата
        assertThat(translations.forRequest(HOTEL, "de").translate("Единична стая")).isEqualTo("Einzelzimmer");
    }

    @Test
    void translateWithoutTranslationReturnsTheTextAsItCame() {
        defaultLanguage(HOTEL, "en");
        common(translation(null, Map.of("bg", "Единична стая", "en", "Single room")));

        // Без резерва през езика по подразбиране – текстът и без това е на някакъв език
        assertThat(translations.forRequest(HOTEL, "de").translate("Единична стая")).isEqualTo("Единична стая");
        assertThat(translations.forRequest(HOTEL, "en").translate("Студио")).isEqualTo("Студио");
        assertThat(translations.forRequest(HOTEL, null).translate("Единична стая")).isEqualTo("Единична стая");
        assertThat(translations.forRequest(HOTEL, "en").translate(null)).isNull();
    }

    @Test
    void messagesWithKeyAreNotUsedForTextsFromOutside() {
        common(translation("rooms.any", Map.of("bg", "стаи", "en", "rooms")));

        assertThat(translations.forRequest(HOTEL, "en").translate("стаи")).isEqualTo("стаи");
    }

    @Test
    void emptyTextsAreSkippedAndLanguagesAreLowerCase() {
        Map<String, String> texts = new HashMap<>();
        texts.put("BG", "Двойна стая");
        texts.put("en", " ");
        texts.put(null, "Нищо");
        common(translation(null, texts), translation("empty", null),
                translation(null, Map.of("bg", "Апартамент", "EN", "Apartment")));

        assertThat(translations.forRequest(HOTEL, "bg").translate("Двойна стая")).isEqualTo("Двойна стая");
        assertThat(translations.forRequest(HOTEL, "en").translate("Двойна стая")).isEqualTo("Двойна стая");
        assertThat(translations.forRequest(HOTEL, "en").translate("Апартамент")).isEqualTo("Apartment");
        assertThat(translations.forRequest(HOTEL, "bg").message("empty")).isEqualTo("empty");
    }

    @Test
    void eachCollectionIsReloadedEveryFiveMinutesAndMongoErrorKeepsPreviousTranslations() {
        common(translation(null, Map.of("bg", "Апартамент")));
        assertThat(translations.forRequest(HOTEL, "en").translate("Апартамент")).isEqualTo("Апартамент");

        hotel(HOTEL, translation(null, Map.of("bg", "Апартамент", "en", "Apartment")));
        clock.advance(Duration.ofMinutes(4));
        assertThat(translations.forRequest(HOTEL, "en").translate("Апартамент")).isEqualTo("Апартамент");

        clock.advance(Duration.ofMinutes(1));
        assertThat(translations.forRequest(HOTEL, "en").translate("Апартамент")).isEqualTo("Apartment");
        verify(repository, times(2)).findCommon();
        verify(repository, times(2)).findByHotelId(HOTEL);

        when(repository.findByHotelId(HOTEL)).thenThrow(new RuntimeException("Atlas down"));
        clock.advance(Duration.ofMinutes(5));
        assertThat(translations.forRequest(HOTEL, "en").translate("Апартамент")).isEqualTo("Apartment");
    }

    private void common(Translation... translations) {
        when(repository.findCommon()).thenReturn(List.of(translations));
    }

    private void hotel(String hotelId, Translation... translations) {
        when(repository.findByHotelId(hotelId)).thenReturn(List.of(translations));
    }

    private void defaultLanguage(String hotelId, String code) {
        when(hotelLanguages.of(hotelId)).thenReturn(new HotelLanguages.Languages(
                List.of(new HotelLanguages.Language(code, code)), code));
    }

    private static Translation translation(String key, Map<String, String> texts) {
        Translation translation = new Translation();
        translation.setKey(key);
        translation.setTexts(texts);
        return translation;
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
