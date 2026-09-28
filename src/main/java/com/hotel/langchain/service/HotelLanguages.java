package com.hotel.langchain.service;

import com.hotel.langchain.model.HotelSettings;
import com.hotel.langchain.repository.HotelSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Езиците на чата за всеки хотел: колекция hotel_settings (модел HotelSettings,
// languages: [{ code: "bg", name: "Български" }, ...], defaultLanguage: "bg").
// Редът в languages е редът в менюто на чата. Хотел без languages (или без документ) – само български.
// defaultLanguage, който липсва или не е в languages – първият език. Пазят се в паметта и се презареждат на REFRESH_AFTER.
@Service
public class HotelLanguages {

    public record Language(String code, String name) {}

    public record Languages(List<Language> languages, String defaultLanguage) {}

    static final Languages BULGARIAN_ONLY = new Languages(List.of(new Language("bg", "Български")), "bg");

    private static final Duration REFRESH_AFTER = Duration.ofMinutes(5);

    private final HotelSettingsRepository hotelSettingsRepository;
    private final Clock clock;
    private volatile Map<String, Languages> languages = Map.of();
    private volatile Instant loadedAt;

    @Autowired
    public HotelLanguages(HotelSettingsRepository hotelSettingsRepository) {
        this(hotelSettingsRepository, Clock.systemUTC());
    }

    HotelLanguages(HotelSettingsRepository hotelSettingsRepository, Clock clock) {
        this.hotelSettingsRepository = hotelSettingsRepository;
        this.clock = clock;
    }

    public Languages of(String hotelId) {
        Instant now = clock.instant();
        if (loadedAt == null || !loadedAt.plus(REFRESH_AFTER).isAfter(now)) {
            reload(now);
        }
        return languages.getOrDefault(hotelId, BULGARIAN_ONLY);
    }

    // Кодът на езика, на който да отговаряме: поисканият, ако хотелът го има, иначе езикът по подразбиране
    public String resolve(String hotelId, String requested) {
        Languages hotel = of(hotelId);
        if (requested != null) {
            String code = requested.trim().toLowerCase(Locale.ROOT);
            for (Language language : hotel.languages()) {
                if (language.code().equals(code)) {
                    return code;
                }
            }
        }
        return hotel.defaultLanguage();
    }

    // Името на езика, както е в менюто („English“) – за system prompt-а; непознат код – самият код
    public String nameOf(String hotelId, String code) {
        return of(hotelId).languages().stream()
                .filter(language -> language.code().equals(code))
                .map(Language::name)
                .findFirst()
                .orElse(code);
    }

    private synchronized void reload(Instant now) {
        // Друга нишка може вече да е заредила езиците
        if (loadedAt != null && loadedAt.plus(REFRESH_AFTER).isAfter(now)) {
            return;
        }
        try {
            Map<String, Languages> loaded = new HashMap<>();
            for (HotelSettings settings : hotelSettingsRepository.findAll()) {
                String hotelId = settings.getHotelId();
                if (hotelId == null || hotelId.isBlank()) {
                    System.err.println("hotel_settings document without hotelId: _id=" + settings.getId());
                    continue;
                }
                try {
                    loaded.put(hotelId, parse(settings));
                } catch (Exception e) {
                    System.err.println("Invalid languages for hotelId=" + hotelId + ": " + e.getMessage());
                }
            }
            languages = Map.copyOf(loaded);
        } catch (Exception e) {
            // Остават старите езици; следващият опит – след REFRESH_AFTER
            System.err.println("Could not load hotel languages from Mongo: " + e);
        }
        loadedAt = now;
    }

    static Languages parse(HotelSettings hotel) {
        List<Language> list = new ArrayList<>();
        if (hotel.getLanguages() != null) {
            for (HotelSettings.Language entry : hotel.getLanguages()) {
                if (entry == null) {
                    continue;
                }
                String code = entry.getCode();
                if (code == null || code.isBlank()) {
                    continue;
                }
                code = code.trim().toLowerCase(Locale.ROOT);
                String duplicate = code;
                if (list.stream().anyMatch(language -> language.code().equals(duplicate))) {
                    continue;
                }
                String name = entry.getName();
                list.add(new Language(code, name == null || name.isBlank() ? code : name.trim()));
            }
        }
        if (list.isEmpty()) {
            return BULGARIAN_ONLY;
        }
        String defaultLanguage = hotel.getDefaultLanguage();
        String code = defaultLanguage == null ? null : defaultLanguage.trim().toLowerCase(Locale.ROOT);
        boolean known = list.stream().anyMatch(language -> language.code().equals(code));
        return new Languages(List.copyOf(list), known ? code : list.get(0).code());
    }
}
