package com.hotel.admin.service;

import com.hotel.admin.service.AdminTokens.AdminToken;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminTokensTest {

    static final String SECRET = "test-admin-secret-0123456789-abcdefghij";

    private final MutableClock clock = new MutableClock();
    private final AdminTokens tokens = new AdminTokens(SECRET, clock);

    @Test
    void issuedTokenIsVerified() {
        String token = tokens.issue("40_robbers", "admin@hotel.bg");

        assertThat(tokens.verify(token)).contains(new AdminToken("40_robbers", "admin@hotel.bg"));
    }

    @Test
    void tokenExpiresAfterEightHours() {
        String token = tokens.issue("40_robbers", "admin@hotel.bg");

        clock.advance(AdminTokens.VALIDITY.minusMinutes(1));
        assertThat(tokens.verify(token)).isPresent();

        clock.advance(Duration.ofMinutes(2));
        assertThat(tokens.verify(token)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        AdminTokens other = new AdminTokens("another-admin-secret-0123456789-abcdefgh", clock);

        assertThat(tokens.verify(other.issue("40_robbers", "admin@hotel.bg"))).isEmpty();
    }

    @Test
    void changedHotelInTheTokenBreaksTheSignature() {
        String[] parts = tokens.issue("40_robbers", "admin@hotel.bg").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("40_robbers", "seven_stars");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThat(tokens.verify(forged)).isEmpty();
    }

    @Test
    void unsignedTokenIsRejected() {
        String unsigned = Jwts.builder().setSubject("admin@hotel.bg").claim("hotelId", "40_robbers")
                .setAudience("booking-ai-admin").setExpiration(new Date(System.currentTimeMillis() + 60_000)).compact();

        assertThat(tokens.verify(unsigned)).isEmpty();
    }

    @Test
    void hotelTokenIsNotAnAdminToken() throws Exception {
        // JWT на сайта на хотела (RS256) и JWT със същия ключ, но без audience на админа
        KeyPair hotelKeys = java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String hotelToken = Jwts.builder().setSubject("admin@hotel.bg").claim("hotelId", "40_robbers")
                .signWith(hotelKeys.getPrivate(), SignatureAlgorithm.RS256).compact();
        String noAudience = Jwts.builder().setSubject("admin@hotel.bg").claim("hotelId", "40_robbers")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact();

        assertThat(tokens.verify(hotelToken)).isEmpty();
        assertThat(tokens.verify(noAudience)).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("not.a.token")).isEmpty();
    }

    @Test
    void missingOrShortSecretStopsTheStart() {
        assertThatThrownBy(() -> new AdminTokens("", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AdminTokens(null, clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AdminTokens("too-short", clock)).isInstanceOf(IllegalStateException.class);
    }
}
