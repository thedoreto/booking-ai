package com.hotel.langchain.service;

import com.hotel.langchain.model.Translation;
import org.junit.jupiter.api.Test;
import com.hotel.langchain.repository.TranslationRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslationServiceTest {

    private final TranslationRepository repository = mock(TranslationRepository.class);
    private final MutableClock clock = new MutableClock();
    private final TranslationService translations = new TranslationService(repository, clock);

    @Test
    void messageFillsParamsInTheRequestedLanguage() {
        translations(translation("rooms.available", Map.of(
                "bg", "Свободни {rooms} за периода {period}.",
                "en", "Available {rooms} {period}.")));

        assertThat(translations.message("rooms.available", "en", Map.of("rooms", "rooms", "period", "from 01.10.2026 to 03.10.2026")))
                .isEqualTo("Available rooms from 01.10.2026 to 03.10.2026.");
        assertThat(translations.message("rooms.available", "bg", Map.of("rooms", "стаи", "period", "от 01.10.2026 до 03.10.2026")))
                .isEqualTo("Свободни стаи за периода от 01.10.2026 до 03.10.2026.");
    }

    @Test
    void messageWithoutTheLanguageIsBulgarianAndUnknownKeyIsTheKey() {
        translations(translation("chat.notFound", Map.of("bg", "Информацията не е намерена.")));

        assertThat(translations.message("chat.notFound", "de")).isEqualTo("Информацията не е намерена.");
        assertThat(translations.message("chat.notFound", null)).isEqualTo("Информацията не е намерена.");
        assertThat(translations.message("chat.unknown", "en")).isEqualTo("chat.unknown");
    }

    @Test
    void messagesWithPrefixGiveTheUiTextsInTheLanguage() {
        translations(translation("ui.send", Map.of("bg", "Изпрати", "en", "Send")),
                translation("ui.cancel", Map.of("bg", "Отказ")),
                translation("booking.error", Map.of("bg", "Грешка", "en", "Error")),
                translation(null, Map.of("bg", "Апартамент", "en", "Apartment")));

        assertThat(translations.messagesWithPrefix("ui.", "en"))
                .isEqualTo(Map.of("ui.send", "Send", "ui.cancel", "Отказ"));
    }

    @Test
    void translateFindsTheTextInAnyLanguage() {
        translations(translation(null, Map.of("bg", "Единична стая", "en", "Single room", "de", "Einzelzimmer")));

        assertThat(translations.translate("Единична стая", "en")).isEqualTo("Single room");
        assertThat(translations.translate("Einzelzimmer", "bg")).isEqualTo("Единична стая");
        assertThat(translations.translate(" Single room ", "de")).isEqualTo("Einzelzimmer");
    }

    @Test
    void translateWithoutTranslationReturnsTheTextAsItCame() {
        translations(translation(null, Map.of("bg", "Единична стая", "en", "Single room")));

        assertThat(translations.translate("Единична стая", "de")).isEqualTo("Единична стая");
        assertThat(translations.translate("Студио", "en")).isEqualTo("Студио");
        assertThat(translations.translate("Единична стая", null)).isEqualTo("Единична стая");
        assertThat(translations.translate(null, "en")).isNull();
    }

    @Test
    void messagesWithKeyAreNotUsedForTextsFromOutside() {
        translations(translation("rooms.any", Map.of("bg", "стаи", "en", "rooms")));

        assertThat(translations.translate("стаи", "en")).isEqualTo("стаи");
    }

    @Test
    void emptyTextsAreSkippedAndLanguagesAreLowerCase() {
        Map<String, String> texts = new HashMap<>();
        texts.put("BG", "Двойна стая");
        texts.put("en", " ");
        texts.put(null, "Нищо");
        translations(translation(null, texts), translation("empty", null),
                translation(null, Map.of("bg", "Апартамент", "EN", "Apartment")));

        assertThat(translations.translate("Двойна стая", "bg")).isEqualTo("Двойна стая");
        assertThat(translations.translate("Двойна стая", "en")).isEqualTo("Двойна стая");
        assertThat(translations.translate("Апартамент", "en")).isEqualTo("Apartment");
        assertThat(translations.message("empty", "bg")).isEqualTo("empty");
    }

    @Test
    void reloadedEveryFiveMinutesAndMongoErrorKeepsPreviousTranslations() {
        translations(translation(null, Map.of("bg", "Апартамент")));
        assertThat(translations.translate("Апартамент", "en")).isEqualTo("Апартамент");

        translations(translation(null, Map.of("bg", "Апартамент", "en", "Apartment")));
        clock.advance(Duration.ofMinutes(4));
        assertThat(translations.translate("Апартамент", "en")).isEqualTo("Апартамент");

        clock.advance(Duration.ofMinutes(1));
        assertThat(translations.translate("Апартамент", "en")).isEqualTo("Apartment");
        verify(repository, times(2)).findAll();

        when(repository.findAll()).thenThrow(new RuntimeException("Atlas down"));
        clock.advance(Duration.ofMinutes(5));
        assertThat(translations.translate("Апартамент", "en")).isEqualTo("Apartment");
    }

    private void translations(Translation... translations) {
        when(repository.findAll()).thenReturn(List.of(translations));
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
