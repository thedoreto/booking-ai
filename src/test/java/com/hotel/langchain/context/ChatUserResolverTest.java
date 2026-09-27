package com.hotel.langchain.context;

import com.hotel.langchain.service.HotelKeys;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Потребител е само този с JWT, подписан с частния ключ на хотела; всичко друго е гост
class ChatUserResolverTest {

    private static final String HOTEL = "seven_stars";
    private static final KeyPair KEYS = keyPair();
    private static final KeyPair OTHER_HOTEL_KEYS = keyPair();

    private final HotelKeys hotelKeys = mock(HotelKeys.class);
    private final ChatUserResolver resolver = new ChatUserResolver(hotelKeys);

    {
        when(hotelKeys.publicKey(HOTEL)).thenReturn(Optional.of(KEYS.getPublic()));
        when(hotelKeys.publicKey("40_robbers")).thenReturn(Optional.of(OTHER_HOTEL_KEYS.getPublic()));
        when(hotelKeys.publicKey("no_key_hotel")).thenReturn(Optional.empty());
    }

    @Test
    void validTokenIsTheUserFromTheToken() {
        String token = token(KEYS.getPrivate(), SignatureAlgorithm.RS256, "user-1", 60_000);

        ChatUser user = resolver.resolve(HOTEL, "Bearer " + token);

        assertThat(user).isEqualTo(new ChatUser("user-1", token));
    }

    @Test
    void noBearerTokenIsGuest() {
        assertThat(resolver.resolve(HOTEL, null)).isNull();
        assertThat(resolver.resolve(HOTEL, "")).isNull();
        assertThat(resolver.resolve(HOTEL, "Basic abc")).isNull();
        assertThat(resolver.resolve(HOTEL, "Bearer ")).isNull();
        assertThat(resolver.resolve(HOTEL, "Bearer not-a-jwt")).isNull();
    }

    @Test
    void tokenOfAnotherHotelIsGuest() {
        String otherHotelToken = token(OTHER_HOTEL_KEYS.getPrivate(), SignatureAlgorithm.RS256, "user-1", 60_000);

        assertThat(resolver.resolve(HOTEL, "Bearer " + otherHotelToken)).isNull();
        assertThat(resolver.resolve("40_robbers", "Bearer " + otherHotelToken)).isNotNull();
    }

    @Test
    void expiredTokenIsGuest() {
        String expired = token(KEYS.getPrivate(), SignatureAlgorithm.RS256, "user-1", -60_000);

        assertThat(resolver.resolve(HOTEL, "Bearer " + expired)).isNull();
    }

    @Test
    void oldHs256AndUnsignedTokensAreGuests() {
        String hs256 = token(Keys.hmacShaKeyFor("old-secret-old-secret-old-secret-12345".getBytes()),
                SignatureAlgorithm.HS256, "user-1", 60_000);
        String unsigned = Jwts.builder().claim("userId", "user-1").compact();

        assertThat(resolver.resolve(HOTEL, "Bearer " + hs256)).isNull();
        assertThat(resolver.resolve(HOTEL, "Bearer " + unsigned)).isNull();
    }

    @Test
    void tokenWithoutUserIdIsGuest() {
        String token = token(KEYS.getPrivate(), SignatureAlgorithm.RS256, null, 60_000);

        assertThat(resolver.resolve(HOTEL, "Bearer " + token)).isNull();
    }

    @Test
    void hotelWithoutKeyHasOnlyGuests() {
        String token = token(KEYS.getPrivate(), SignatureAlgorithm.RS256, "user-1", 60_000);

        assertThat(resolver.resolve("no_key_hotel", "Bearer " + token)).isNull();
    }

    private static String token(Key key, SignatureAlgorithm algorithm, String userId, long expiresInMs) {
        var builder = Jwts.builder().setExpiration(new Date(System.currentTimeMillis() + expiresInMs));
        if (userId != null) {
            builder.claim("userId", userId);
        }
        return builder.signWith(key, algorithm).compact();
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
