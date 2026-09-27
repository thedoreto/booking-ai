package com.hotel.langchain.context;

import com.hotel.langchain.service.HotelKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.Optional;

// Кой пише в чата: JWT-то от header Authorization се проверява с публичния ключ на хотела (HotelKeys).
// Всичко, което не минава проверката – невалиден подпис, токен на друг хотел, изтекъл, стар HS256, без userId,
// хотел без ключ – е гост. Така guest.isActive и userId в логовете не зависят от това какво праща клиентът.
@Component
public class ChatUserResolver {

    private final HotelKeys hotelKeys;

    public ChatUserResolver(HotelKeys hotelKeys) {
        this.hotelKeys = hotelKeys;
    }

    // null – гост
    public ChatUser resolve(String hotelId, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        Optional<PublicKey> key = hotelKeys.publicKey(hotelId);
        if (key.isEmpty()) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key.get()).build().parseClaimsJws(token).getBody();
            String userId = claims.get("userId", String.class);
            return userId != null && !userId.isBlank() ? new ChatUser(userId, token) : null;
        } catch (Exception e) {
            // Невалиден или изтекъл токен – продължава като гост; при действие ще получи „влезте в профила си“
            return null;
        }
    }
}
