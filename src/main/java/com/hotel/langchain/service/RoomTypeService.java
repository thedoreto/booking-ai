package com.hotel.langchain.service;

import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

// Типовете стаи на хотела идват от booking-system (event get_room_types) и се кешират за всеки хотел.
// Нов тип в бекенда се появява тук най-късно след CACHE_TTL, без промяна в booking-ai.
@Service
public class RoomTypeService {

    public record RoomType(String code, String name) {}

    private record CachedTypes(List<RoomType> types, Instant fetchedAt) {}

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    // След неуспешна заявка не питаме бекенда отново веднага, за да не чака всеки чат по 5s timeout
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private final HotelBackendClient backendClient;
    private final Map<String, CachedTypes> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    public RoomTypeService(HotelBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    // Празен списък, ако бекендът не е отговорил нито веднъж
    public List<RoomType> getRoomTypes(String hotelId) {
        CachedTypes cached = cache.get(hotelId);
        Instant now = Instant.now();
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.types();
        }
        Instant failedAt = lastFailure.get(hotelId);
        if (failedAt != null && failedAt.plus(RETRY_AFTER_FAILURE).isAfter(now)) {
            return cached != null ? cached.types() : List.of();
        }

        try {
            List<RoomType> types = toRoomTypes(backendClient.request(hotelId, "get_room_types", Map.of()));
            cache.put(hotelId, new CachedTypes(types, now));
            lastFailure.remove(hotelId);
            return types;
        } catch (HotelBackendException | TimeoutException | InterruptedException e) {
            System.err.println("Could not load room types for hotelId=" + hotelId + ": " + e);
            lastFailure.put(hotelId, now);
            // Остарелият списък е по-добър от никакъв
            return cached != null ? cached.types() : List.of();
        }
    }

    // Кодът на типа (без значение от главни/малки букви), ако хотелът има такъв тип; иначе null (всички типове).
    // Ако типовете не са известни (бекендът не отговаря), подаваме кода нататък – booking-system ще го провери.
    public String normalize(String hotelId, String roomType) {
        if (roomType == null || roomType.isBlank() || "null".equalsIgnoreCase(roomType.trim())) {
            return null;
        }
        String code = roomType.trim().toUpperCase();
        List<RoomType> types = getRoomTypes(hotelId);
        if (types.isEmpty()) {
            return code;
        }
        return types.stream().anyMatch(t -> t.code().equals(code)) ? code : null;
    }

    public String nameOf(String hotelId, String code) {
        return getRoomTypes(hotelId).stream()
                .filter(t -> t.code().equals(code))
                .map(RoomType::name)
                .findFirst()
                .orElse(code);
    }

    // За system prompt-а: "SINGLE (Единична стая), DOUBLE (Двойна стая)"
    public String describeForPrompt(String hotelId) {
        List<RoomType> types = getRoomTypes(hotelId);
        if (types.isEmpty()) {
            return "няма информация в момента";
        }
        return types.stream()
                .map(t -> t.code() + " (" + t.name() + ")")
                .collect(Collectors.joining(", "));
    }

    private List<RoomType> toRoomTypes(Object data) {
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> (Map<?, ?>) item)
                .filter(map -> map.get("code") != null)
                .map(map -> new RoomType(
                        String.valueOf(map.get("code")),
                        map.get("name") != null ? String.valueOf(map.get("name")) : String.valueOf(map.get("code"))))
                .toList();
    }
}
