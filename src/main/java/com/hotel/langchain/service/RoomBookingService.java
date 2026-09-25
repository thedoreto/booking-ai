package com.hotel.langchain.service;

import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

// Търсене на свободни стаи и създаване на резервации директно през booking-system, без LLM.
// Резултатът е UiAction: текст за чата + (по избор) действие и данни, които UI показва.
@Service
public class RoomBookingService {

    public static final String SELECT_ROOMS_ACTION = "SELECT_ROOMS";
    public static final String BOOKING_CONFIRMED_ACTION = "BOOKING_CONFIRMED";
    public static final String MY_BOOKINGS_ACTION = "MY_BOOKINGS";
    public static final String BOOKING_CANCELED_ACTION = "BOOKING_CANCELED";

    private static final DateTimeFormatter BG_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String BACKEND_UNAVAILABLE = "Хотелската система не отговаря в момента. Моля, опитайте отново след малко.";

    private final HotelBackendClient backendClient;
    private final RoomTypeService roomTypeService;
    private final ChatHistoryService chatHistoryService;

    public RoomBookingService(HotelBackendClient backendClient, RoomTypeService roomTypeService,
                              ChatHistoryService chatHistoryService) {
        this.backendClient = backendClient;
        this.roomTypeService = roomTypeService;
        this.chatHistoryService = chatHistoryService;
    }

    public UiAction findAvailableRooms(String hotelId, String startDateStr, String endDateStr, String roomTypeStr) {
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

        String roomType = roomTypeService.normalize(hotelId, roomTypeStr);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("startDate", startDate.toString());
            params.put("endDate", endDate.toString());
            if (roomType != null) {
                params.put("roomType", roomType);
            }
            Object rooms = backendClient.request(hotelId, "get_available_rooms_by_dates", params);
            String period = "от " + startDate.format(BG_DATE) + " до " + endDate.format(BG_DATE);
            String what = roomType != null
                    ? "стаи от тип „" + roomTypeService.nameOf(hotelId, roomType) + "“"
                    : "стаи";

            if (!(rooms instanceof List<?> roomList) || roomList.isEmpty()) {
                return textOnly("За периода " + period + " няма свободни " + what + ". Опитайте с други дати"
                        + (roomType != null ? " или друг тип стая." : "."));
            }
            Map<String, Object> data = new HashMap<>();
            data.put("startDate", startDate.toString());
            data.put("endDate", endDate.toString());
            data.put("rooms", roomList);
            if (roomType != null) {
                data.put("roomType", roomType);
            }
            return new UiAction(
                    SELECT_ROOMS_ACTION,
                    "Свободни " + what + " за периода " + period + ". Изберете една или повече стаи:",
                    data);
        } catch (HotelBackendException e) {
            return textOnly("Грешка при търсене на свободни стаи: " + translateBackendError(e.getMessage()));
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
            String reply = confirmationText(bookings, startDate, endDate);
            chatHistoryService.record(hotelId, userId, "Резервирай " + roomNumbersText(bookings) + " от "
                    + startDate.format(BG_DATE) + " до " + endDate.format(BG_DATE) + ".", reply);
            return new UiAction(BOOKING_CONFIRMED_ACTION, reply, bookings);
        } catch (HotelBackendException e) {
            return textOnly("Резервацията не беше направена: " + translateBackendError(e.getMessage()));
        } catch (TimeoutException | InterruptedException e) {
            // Не знаем дали бекендът я е записал – потребителят трябва да провери
            return textOnly("Хотелската система не потвърди резервацията навреме. "
                    + "Моля, проверете „Моите резервации“, преди да опитате отново.");
        }
    }

    // Предстоящите потвърдени резервации на потребителя – за картичките с бутон „Откажи“
    public UiAction myBookings(String hotelId, String userId) {
        if (userId == null || userId.isBlank()) {
            return textOnly("Моля, влезте в профила си, за да видите вашите резервации.");
        }
        try {
            Object bookings = backendClient.request(hotelId, "get_upcoming_bookings", Map.of("userId", userId));
            if (!(bookings instanceof List<?> list) || list.isEmpty()) {
                return textOnly("Нямате предстоящи резервации.");
            }
            return new UiAction(MY_BOOKINGS_ACTION,
                    "Вашите предстоящи резервации. Резервация може да се откаже най-късно в деня преди настаняването.",
                    Map.of("bookings", list));
        } catch (HotelBackendException e) {
            return textOnly("Грешка при зареждане на резервациите: " + translateBackendError(e.getMessage()));
        } catch (TimeoutException | InterruptedException e) {
            return textOnly(BACKEND_UNAVAILABLE);
        }
    }

    public UiAction cancelBooking(String hotelId, String userId, String bookingId) {
        if (userId == null || userId.isBlank()) {
            return textOnly("Моля, влезте в профила си, за да откажете резервация.");
        }
        if (bookingId == null || bookingId.isBlank()) {
            return textOnly("Не е избрана резервация.");
        }
        try {
            Object booking = backendClient.request(hotelId, "cancel_booking", Map.of(
                    "userId", userId,
                    "bookingId", bookingId
            ));
            String description = describeBooking(hotelId, booking);
            String reply = "Резервацията " + description + " е отказана.";
            chatHistoryService.record(hotelId, userId, "Откажи резервацията " + description + ".", reply);
            return new UiAction(BOOKING_CANCELED_ACTION, reply, booking);
        } catch (HotelBackendException e) {
            return textOnly("Резервацията не беше отказана: " + translateBackendError(e.getMessage()));
        } catch (TimeoutException | InterruptedException e) {
            // Не знаем дали бекендът я е отказал – потребителят трябва да провери
            return textOnly("Хотелската система не потвърди отказа навреме. "
                    + "Моля, проверете „Моите резервации“, преди да опитате отново.");
        }
    }

    // „за стая №12 (Двойна стая) от 30.10.2026 до 03.11.2026“
    private String describeBooking(String hotelId, Object booking) {
        if (!(booking instanceof Map<?, ?> map)) {
            return "";
        }
        StringBuilder text = new StringBuilder("за стая №").append(map.get("roomNumber"));
        if (map.get("roomType") != null) {
            text.append(" (").append(roomTypeService.nameOf(hotelId, String.valueOf(map.get("roomType")))).append(")");
        }
        LocalDate checkIn = parseDateOrNull(map.get("checkInDate"));
        LocalDate checkOut = parseDateOrNull(map.get("checkOutDate"));
        if (checkIn != null && checkOut != null) {
            text.append(" от ").append(checkIn.format(BG_DATE)).append(" до ").append(checkOut.format(BG_DATE));
        }
        return text.toString();
    }

    private LocalDate parseDateOrNull(Object value) {
        try {
            return value != null ? LocalDate.parse(String.valueOf(value)) : null;
        } catch (DateTimeParseException e) {
            return null;
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

    // „стая №12“ или „стаи №12, №15“
    private String roomNumbersText(Object bookings) {
        List<String> numbers = bookings instanceof List<?> list
                ? list.stream()
                        .filter(item -> item instanceof Map<?, ?>)
                        .map(item -> "№" + ((Map<?, ?>) item).get("roomNumber"))
                        .toList()
                : List.of();
        return (numbers.size() == 1 ? "стая " : "стаи ") + String.join(", ", numbers);
    }

    // booking-system връща причините на английски (ResponseStatusException reason)
    private String translateBackendError(String reason) {
        return switch (reason) {
            case "Room not available" -> "някоя от избраните стаи вече е заета. Моля, потърсете отново свободни стаи.";
            case "User not found" -> "потребителят не е намерен.";
            case "Room not found" -> "стаята не е намерена.";
            case "Invalid dates" -> "невалиден период.";
            case "Invalid room type" -> "невалиден тип стая.";
            case "Booking not found" -> "резервацията не е намерена.";
            case "Booking is already canceled" -> "резервацията вече е отказана.";
            case "Cancellation deadline passed" -> "резервация може да се откаже най-късно в деня преди настаняването.";
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
