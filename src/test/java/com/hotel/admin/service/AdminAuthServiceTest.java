package com.hotel.admin.service;

import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminAuthService.LoginResult;
import com.hotel.admin.service.AdminAuthService.Outcome;
import com.hotel.langchain.model.HotelSettings;
import com.hotel.langchain.repository.HotelSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuthServiceTest {

    private static final String PASSWORD = "Correct-Horse-7";
    // Нисък cost – само за да са бързи тестовете
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private final HotelSettingsRepository repository = mock(HotelSettingsRepository.class);
    private final MutableClock clock = new MutableClock();
    private final AdminTokens tokens = new AdminTokens(AdminTokensTest.SECRET, clock);
    private final AdminAuthService service = new AdminAuthService(repository, tokens, ENCODER, clock);

    @BeforeEach
    void hotels() {
        when(repository.findByHotelId(anyString())).thenReturn(Optional.empty());
        hotel("40_robbers", "The.Doreto@gmail.com", ENCODER.encode(PASSWORD));
        hotel("seven_stars", "the.doreto@gmail.com", ENCODER.encode("other-password"));
    }

    @Test
    void correctPasswordGivesTokenForThatHotel() {
        LoginResult result = service.login("40_robbers", "the.doreto@gmail.com", PASSWORD);

        assertThat(result.outcome()).isEqualTo(Outcome.OK);
        assertThat(result.admin()).isEqualTo(new Admin("40_robbers", "the.doreto@gmail.com", "Теодора"));
        assertThat(service.verify(result.token())).contains(result.admin());
    }

    @Test
    void emailIgnoresCaseAndSpaces() {
        assertThat(service.login(" 40_robbers ", "  THE.doreto@Gmail.com ", PASSWORD).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void sameAdminOnTwoHotelsHasAPasswordForEach() {
        assertThat(service.login("seven_stars", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(service.login("seven_stars", "the.doreto@gmail.com", "other-password").admin().hotelId())
                .isEqualTo("seven_stars");
    }

    @Test
    void everyMistakeLooksTheSame() {
        assertThat(service.login("40_robbers", "the.doreto@gmail.com", "wrong").outcome()).isEqualTo(Outcome.INVALID);
        assertThat(service.login("40_robbers", "someone@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(service.login("fake_hotel", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(service.login("40_robbers", "the.doreto@gmail.com", "").outcome()).isEqualTo(Outcome.INVALID);
        assertThat(service.login(null, null, null).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void hotelWithoutAdminOrHashHasNoLogin() {
        when(repository.findByHotelId("no_admin")).thenReturn(Optional.of(new HotelSettings()));
        hotel("no_hash", "the.doreto@gmail.com", null);
        hotel("plain_text", "the.doreto@gmail.com", PASSWORD);

        assertThat(service.login("no_admin", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.INVALID);
        assertThat(service.login("no_hash", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.INVALID);
        // Парола, записана като текст вместо хеш, не пуска никого
        assertThat(service.login("plain_text", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.INVALID);
    }

    @Test
    void fiveWrongPasswordsLockForFifteenMinutesEvenWithTheRightOne() {
        for (int i = 0; i < AdminAuthService.MAX_FAILED_ATTEMPTS; i++) {
            service.login("40_robbers", "the.doreto@gmail.com", "wrong-" + i);
        }
        assertThat(service.login("40_robbers", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.LOCKED);
        // Другият хотел на същия имейл не е заключен
        assertThat(service.login("seven_stars", "the.doreto@gmail.com", "other-password").outcome()).isEqualTo(Outcome.OK);

        clock.advance(AdminAuthService.LOCK_WINDOW);
        assertThat(service.login("40_robbers", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void successfulLoginResetsTheCount() {
        for (int i = 0; i < AdminAuthService.MAX_FAILED_ATTEMPTS - 1; i++) {
            service.login("40_robbers", "the.doreto@gmail.com", "wrong");
        }
        service.login("40_robbers", "the.doreto@gmail.com", PASSWORD);
        for (int i = 0; i < AdminAuthService.MAX_FAILED_ATTEMPTS - 1; i++) {
            service.login("40_robbers", "the.doreto@gmail.com", "wrong");
        }

        assertThat(service.login("40_robbers", "the.doreto@gmail.com", PASSWORD).outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    void removedOrChangedAdminLosesAccessWithAValidToken() {
        String token = service.login("40_robbers", "the.doreto@gmail.com", PASSWORD).token();

        hotel("40_robbers", "new.admin@gmail.com", ENCODER.encode(PASSWORD));
        assertThat(service.verify(token)).isEmpty();

        when(repository.findByHotelId("40_robbers")).thenReturn(Optional.of(new HotelSettings()));
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service.login("40_robbers", "the.doreto@gmail.com", PASSWORD).token();

        clock.advance(AdminTokens.VALIDITY.plus(Duration.ofSeconds(1)));

        assertThat(service.verify(token)).isEmpty();
    }

    private void hotel(String hotelId, String email, String passwordHash) {
        HotelSettings.Admin admin = new HotelSettings.Admin();
        admin.setEmail(email);
        admin.setName("Теодора");
        admin.setPasswordHash(passwordHash);
        HotelSettings settings = new HotelSettings();
        settings.setHotelId(hotelId);
        settings.setAdmin(admin);
        when(repository.findByHotelId(hotelId)).thenReturn(Optional.of(settings));
    }
}
