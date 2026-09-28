package com.hotel.admin.service;

import com.hotel.langchain.model.HotelSettings;
import com.hotel.langchain.repository.HotelSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Входът в админ панела: хотел + имейл + парола срещу admin в hotel_settings на този хотел (BCrypt).
// Отделен е от потребителите на сайта на хотела – booking-system и Kafka не участват.
// Всяка грешка (непознат хотел, хотел без админ, друг имейл, грешна парола) изглежда еднакво отвън,
// за да не може да се разбере кои хотели и имейли съществуват.
@Service
public class AdminAuthService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    // Над толкова записа за грешни опити се чистят изтеклите (измислени имейли не пълнят паметта безкрайно)
    private static final int CLEANUP_THRESHOLD = 10_000;

    public enum Outcome { OK, INVALID, LOCKED }

    // Влезлият админ: hotelId, email и name от hotel_settings
    public record Admin(String hotelId, String email, String name) {}

    // token и admin – само при OK
    public record LoginResult(Outcome outcome, String token, Admin admin) {}

    private record Failures(int count, Instant firstAt) {}

    private final HotelSettingsRepository hotelSettingsRepository;
    private final AdminTokens adminTokens;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    // Хеш, с който се сравнява паролата, когато няма админ: отговорът отнема толкова време,
    // колкото и при истински админ, и по времето не се познава дали хотелът/имейлът съществуват
    private final String dummyHash;
    // Грешните опити по хотел + имейл (в RAM – при рестарт започват отначало)
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    @Autowired
    public AdminAuthService(HotelSettingsRepository hotelSettingsRepository, AdminTokens adminTokens) {
        this(hotelSettingsRepository, adminTokens, new BCryptPasswordEncoder(), Clock.systemUTC());
    }

    AdminAuthService(HotelSettingsRepository hotelSettingsRepository, AdminTokens adminTokens,
                     PasswordEncoder passwordEncoder, Clock clock) {
        this.hotelSettingsRepository = hotelSettingsRepository;
        this.adminTokens = adminTokens;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("no-admin-" + System.nanoTime());
    }

    public LoginResult login(String hotelId, String email, String password) {
        if (isBlank(hotelId) || isBlank(email) || isBlank(password)) {
            return new LoginResult(Outcome.INVALID, null, null);
        }
        String attemptKey = hotelId.trim() + "|" + normalize(email);
        Instant now = clock.instant();
        if (isLocked(attemptKey, now)) {
            return new LoginResult(Outcome.LOCKED, null, null);
        }

        Optional<HotelSettings.Admin> stored = storedAdmin(hotelId.trim(), email);
        String hash = stored.map(HotelSettings.Admin::getPasswordHash).filter(h -> !h.isBlank()).orElse(null);
        // Сравнява се винаги (с dummyHash, ако няма админ), за да отнема еднакво време
        boolean matches = passwordEncoder.matches(password, hash != null ? hash : dummyHash) && hash != null;
        if (!matches) {
            recordFailure(attemptKey, now);
            return new LoginResult(Outcome.INVALID, null, null);
        }
        failures.remove(attemptKey);
        Admin admin = toAdmin(hotelId.trim(), stored.get());
        return new LoginResult(Outcome.OK, adminTokens.issue(admin.hotelId(), admin.email()), admin);
    }

    // Админът по токена. Проверява и в Mongo, че още е админ на хотела – махнат или сменен админ губи достъп веднага.
    public Optional<Admin> verify(String token) {
        return adminTokens.verify(token)
                .flatMap(t -> storedAdmin(t.hotelId(), t.email()).map(a -> toAdmin(t.hotelId(), a)));
    }

    // Админът на хотела, ако имейлът е неговият (без значение главни/малки букви)
    private Optional<HotelSettings.Admin> storedAdmin(String hotelId, String email) {
        return hotelSettingsRepository.findByHotelId(hotelId)
                .map(HotelSettings::getAdmin)
                .filter(a -> a.getEmail() != null && normalize(a.getEmail()).equals(normalize(email)));
    }

    private static Admin toAdmin(String hotelId, HotelSettings.Admin stored) {
        return new Admin(hotelId, normalize(stored.getEmail()), stored.getName());
    }

    private boolean isLocked(String attemptKey, Instant now) {
        Failures f = failures.get(attemptKey);
        return f != null && f.count() >= MAX_FAILED_ATTEMPTS && now.isBefore(f.firstAt().plus(LOCK_WINDOW));
    }

    private void recordFailure(String attemptKey, Instant now) {
        if (failures.size() > CLEANUP_THRESHOLD) {
            failures.values().removeIf(f -> !now.isBefore(f.firstAt().plus(LOCK_WINDOW)));
        }
        failures.compute(attemptKey, (k, f) -> f == null || !now.isBefore(f.firstAt().plus(LOCK_WINDOW))
                ? new Failures(1, now)
                : new Failures(f.count() + 1, f.firstAt()));
        if (failures.get(attemptKey).count() == MAX_FAILED_ATTEMPTS) {
            System.out.println("Admin login locked for " + LOCK_WINDOW.toMinutes() + " min: hotelId=" + attemptKey.split("\\|")[0]);
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
