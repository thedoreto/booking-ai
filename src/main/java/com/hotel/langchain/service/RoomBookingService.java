package com.hotel.langchain.service;

import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

// Търсене на свободни стаи и създаване на резервации директно през booking-system, без LLM.
// Резултатът е UiAction: текст за чата + (по избор) действие и данни, които UI показва.
@Service
public class RoomBookingService {

    public static final String SELECT_ROOMS_ACTION = "SELECT_ROOMS";
    public static final String BOOKING_CONFIRMED_ACTION = "BOOKING_CONFIRMED";

    private static final DateTimeFormatter BG_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String BACKEND_UNAVAILABLE = "Хотелската система не отговаря в момента. Моля, опитайте отново след малко.";

    private final HotelBackendClient backendClient;

    public RoomBookingService(HotelBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    public UiAction findAvailableRooms(String hotelId, String startDateStr, String endDateStr) {
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } catch (DateTimeParseException | NullPointerException e) {
            return textOnly("Невалидни дати. Моля, изберете период от календара.");
        }
        String periodError = validatePeriod(startDate, endDate);
        if (periodError != null) {
            return textOnly(periodError);
        }

        try {
            Object rooms = backendClient.request(hotelId, "get_available_rooms_by_dates", Map.of(
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));
            String period = "от " + startDate.format(BG_DATE) + " до " + endDate.format(BG_DATE);

            if (!(rooms instanceof List<?> roomList) || roomList.isEmpty()) {
                return textOnly("За периода " + period + " няма свободни стаи. Опитайте с други дати.");
            }
            return new UiAction(
                    SELECT_ROOMS_ACTION,
                    "Свободни стаи за периода " + period + ". Изберете една или повече стаи:",
                    Map.of(
                            "startDate", startDate.toString(),
                            "endDate", endDate.toString(),
                            "rooms", roomList
                    ));
        } catch (HotelBackendException e) {
            return textOnly("Грешка при търсене на свободни стаи: " + e.getMessage());
        } catch (TimeoutException | InterruptedException e) {
            return textOnly(BACKEND_UNAVAILABLE);
        }
    }

    public UiAction createBookings(String hotelId, String userId, String startDateStr, String endDateStr,
                                   List<String> roomIds) {
        if (userId == null || userId.isBlank()) {
            return textOnly("Моля, влезте в профила си, за да направите резервация.");
        }
        if (roomIds == null || roomIds.isEmpty()) {
            return textOnly("Моля, изберете поне една стая.");
        }
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } catch (DateTimeParseException | NullPointerException e) {
            return textOnly("Невалидни дати за резервацията.");
        }
        String periodError = validatePeriod(startDate, endDate);
        if (periodError != null) {
            return textOnly(periodError);
        }

        try {
            Object bookings = backendClient.request(hotelId, "create_booking", Map.of(
                    "userId", userId,
                    "roomIds", roomIds,
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));
            return new UiAction(BOOKING_CONFIRMED_ACTION, confirmationText(bookings, startDate, endDate), bookings);
        } catch (HotelBackendException e) {
            return textOnly("Резервацията не беше направена: " + translateBackendError(e.getMessage()));
        } catch (TimeoutException | InterruptedException e) {
            // Не знаем дали бекендът я е записал – потребителят трябва да провери
            return textOnly("Хотелската система не потвърди резервацията навреме. "
                    + "Моля, проверете „Моите резервации“, преди да опитате отново.");
        }
    }

    private String validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (!startDate.isBefore(endDate)) {
            return "Датата на напускане трябва да е след датата на настаняване.";
        }
        if (startDate.isBefore(LocalDate.now())) {
            return "Датата на настаняване не може да е в миналото.";
        }
        return null;
    }

    private String confirmationText(Object bookings, LocalDate startDate, LocalDate endDate) {
        StringBuilder text = new StringBuilder("Резервацията е потвърдена за периода от ")
                .append(startDate.format(BG_DATE)).append(" до ").append(endDate.format(BG_DATE)).append(":");
        double total = 0;
        if (bookings instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> booking) {
                    Object price = booking.get("totalPrice");
                    text.append("\n• Стая №").append(booking.get("roomNumber"))
                            .append(" – ").append(String.format("%.2f", toDouble(price))).append(" лв.");
                    total += toDouble(price);
                }
            }
        }
        return text.append("\nОбща сума: ").append(String.format("%.2f", total)).append(" лв.").toString();
    }

    // booking-system връща причините на английски (ResponseStatusException reason)
    private String translateBackendError(String reason) {
        return switch (reason) {
            case "Room not available" -> "някоя от избраните стаи вече е заета. Моля, потърсете отново свободни стаи.";
            case "User not found" -> "потребителят не е намерен.";
            case "Room not found" -> "стаята не е намерена.";
            case "Invalid dates" -> "невалиден период.";
            default -> reason;
        };
    }

    private double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private UiAction textOnly(String reply) {
        return new UiAction(null, reply, null);
    }
}
