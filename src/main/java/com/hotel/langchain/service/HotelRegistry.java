package com.hotel.langchain.service;

import com.hotel.knowledge.repository.KnowledgeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

// Кои хотели съществуват: хотел е всеки, който има колекция knowledge_<hotelId>.
// hotelId идва от body-то без проверка, а логовете пишат в logs_<hotelId> – без тази проверка
// всеки измислен hotelId би създал нова колекция в Mongo (и би стигнал до Gemini и Kafka).
// Пазим множеството на съществуващите хотели, а не отговор за всяко питано име – така паметта
// не расте от измислени имена, а Mongo се пита най-много веднъж на REFRESH_AFTER за непознат хотел.
@Service
public class HotelRegistry {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Duration REFRESH_AFTER = Duration.ofMinutes(1);

    private final KnowledgeRepository knowledgeRepository;
    private final Clock clock;
    private volatile Set<String> knownHotels = Set.of();
    private volatile Instant loadedAt;

    @Autowired
    public HotelRegistry(KnowledgeRepository knowledgeRepository) {
        this(knowledgeRepository, Clock.systemUTC());
    }

    HotelRegistry(KnowledgeRepository knowledgeRepository, Clock clock) {
        this.knowledgeRepository = knowledgeRepository;
        this.clock = clock;
    }

    public boolean isKnown(String hotelId) {
        if (hotelId == null || !SAFE_ID.matcher(hotelId).matches()) {
            return false;
        }
        if (knownHotels.contains(hotelId)) {
            return true;
        }
        // Нов хотел (току-що добавени знания) се появява след най-много REFRESH_AFTER
        refreshIfStale();
        return knownHotels.contains(hotelId);
    }

    private synchronized void refreshIfStale() {
        Instant now = clock.instant();
        if (loadedAt != null && loadedAt.plus(REFRESH_AFTER).isAfter(now)) {
            return;
        }
        try {
            knownHotels = knowledgeRepository.findHotelIds();
        } catch (Exception e) {
            // Остава старият списък; следващият опит – след REFRESH_AFTER
            System.err.println("Could not load hotels from Mongo: " + e);
        }
        loadedAt = now;
    }
}
