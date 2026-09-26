package com.hotel.langchain.context;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

// Влезлият потребител според JWT-то от UI (Authorization: Bearer ...). null – гост.
// booking-ai НЕ проверява подписа: токенът отива през Kafka и booking-system взима потребителя от него
// (действията от чуждо име се отказват там). id е прочетен от токена без проверка – само за логовете.
public record ChatUser(String id, String token) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ChatUser fromAuthorization(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : new ChatUser(readUserId(token), token);
    }

    // Ключ за паметта на разговора – от целия токен, не от id: подправен токен с чужд userId
    // не може да стигне до чужда памет
    public String memoryKey() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Claim userId от средната част на JWT-то; null, ако не се чете
    private static String readUserId(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            Map<?, ?> claims = MAPPER.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            return claims.get("userId") instanceof String userId && !userId.isBlank() ? userId : null;
        } catch (Exception e) {
            return null;
        }
    }
}
