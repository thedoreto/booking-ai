package com.hotel.langchain.service;

import com.hotel.langchain.model.HotelSettings;
import com.hotel.langchain.repository.HotelSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// Публичните ключове, с които всеки хотел проверява своите JWT: колекция hotel_settings (модел HotelSettings,
// jwtPublicKey: <PEM или base64 на един ред>). Частният ключ е само в booking-system на хотела,
// така че booking-ai може да проверява токените, но не и да ги издава. Ключовете се пазят в паметта:
// презареждат се на REFRESH_AFTER, а за хотел без ключ – най-рано след RETRY_MISSING_AFTER (нов или сменен ключ
// влиза без рестарт, а заявки за хотел без ключ не питат Mongo всеки път).
@Service
public class HotelKeys {

    private static final Duration REFRESH_AFTER = Duration.ofMinutes(5);
    private static final Duration RETRY_MISSING_AFTER = Duration.ofMinutes(1);

    private final HotelSettingsRepository hotelSettingsRepository;
    private final Clock clock;
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant loadedAt;

    @Autowired
    public HotelKeys(HotelSettingsRepository hotelSettingsRepository) {
        this(hotelSettingsRepository, Clock.systemUTC());
    }

    HotelKeys(HotelSettingsRepository hotelSettingsRepository, Clock clock) {
        this.hotelSettingsRepository = hotelSettingsRepository;
        this.clock = clock;
    }

    public Optional<PublicKey> publicKey(String hotelId) {
        Instant now = clock.instant();
        PublicKey key = keys.get(hotelId);
        Duration maxAge = key != null ? REFRESH_AFTER : RETRY_MISSING_AFTER;
        if (loadedAt == null || !loadedAt.plus(maxAge).isAfter(now)) {
            reload(now, maxAge);
            key = keys.get(hotelId);
        }
        return Optional.ofNullable(key);
    }

    private synchronized void reload(Instant now, Duration maxAge) {
        // Друга нишка може вече да е заредила ключовете
        if (loadedAt != null && loadedAt.plus(maxAge).isAfter(now)) {
            return;
        }
        try {
            Map<String, PublicKey> loaded = new HashMap<>();
            for (HotelSettings settings : hotelSettingsRepository.findAll()) {
                String hotelId = settings.getHotelId();
                if (hotelId == null || hotelId.isBlank()) {
                    System.err.println("hotel_settings document without hotelId: _id=" + settings.getId());
                    continue;
                }
                try {
                    loaded.put(hotelId, parse(settings.getJwtPublicKey()));
                } catch (Exception e) {
                    System.err.println("Invalid jwtPublicKey for hotelId=" + hotelId + ": " + e.getMessage());
                }
            }
            if (!loaded.keySet().equals(keys.keySet())) {
                System.out.println("JWT public keys loaded for hotels: " + loaded.keySet());
            }
            keys = Map.copyOf(loaded);
        } catch (Exception e) {
            // Остават старите ключове; следващият опит – след maxAge
            System.err.println("Could not load hotel JWT keys from Mongo: " + e);
        }
        loadedAt = now;
    }

    // PEM (с -----BEGIN PUBLIC KEY----- редовете) или само base64
    static PublicKey parse(String text) throws GeneralSecurityException {
        if (text == null || text.isBlank()) {
            throw new GeneralSecurityException("missing");
        }
        String base64 = text.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("not PEM/base64", e);
        }
    }
}
