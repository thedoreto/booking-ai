package com.hotel.admin.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

// Токените на админ панела: JWT (HS256), подписан с admin.jwt-secret (env ADMIN_JWT_SECRET). Един ключ и подписва,
// и проверява – и двете прави само booking-ai. Нищо общо с JWT-тата на хотелите (RS256, ключът на хотела).
// Без ключ приложението не стартира – по-добре, отколкото токени, подписани с празен или слаб ключ.
@Component
public class AdminTokens {

    static final Duration VALIDITY = Duration.ofHours(8);
    // Отличава токена на админа от всеки друг JWT, подписан по случайност със същия ключ
    private static final String AUDIENCE = "booking-ai-admin";
    private static final int MIN_SECRET_BYTES = 32;

    // Кой е влязъл: хотелът и имейлът на админа от токена
    public record AdminToken(String hotelId, String email) {}

    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public AdminTokens(@Value("${admin.jwt-secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    AdminTokens(String secret, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("admin.jwt-secret (env ADMIN_JWT_SECRET) is missing or shorter than "
                    + MIN_SECRET_BYTES + " bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String issue(String hotelId, String email) {
        Instant now = clock.instant();
        return Jwts.builder()
                .setSubject(email)
                .claim("hotelId", hotelId)
                .setAudience(AUDIENCE)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(VALIDITY)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // Празно – невалиден подпис, изтекъл, без подпис, друг вид токен или липсващи полета
    public Optional<AdminToken> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireAudience(AUDIENCE)
                    .setClock(() -> Date.from(clock.instant()))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String hotelId = claims.get("hotelId", String.class);
            String email = claims.getSubject();
            if (hotelId == null || hotelId.isBlank() || email == null || email.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AdminToken(hotelId, email));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
