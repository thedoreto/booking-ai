package com.hotel.langchain.service;

import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.TenantContext.UiAction;
import com.hotel.langchain.log.ChatLogEntry;
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

    private final HotelBackendClient backendClient;
    private final RoomTypeService roomTypeService;
    private final ChatHistoryService chatHistoryService;
    private final TranslationService translations;

    public RoomBookingService(HotelBackendClient backendClient, RoomTypeService roomTypeService,
                              ChatHistoryService chatHistoryService, TranslationService translations) {
        this.backendClient = backendClient;
        this.roomTypeService = roomTypeService;
        this.chatHistoryService = chatHistoryService;
        this.translations = translations;
    }

    // language – кодът на езика на отговора (HotelLanguages.resolve); текстовете са в колекция translations
    public UiAction findAvailableRooms(String hotelId, String startDateStr, String endDateStr, String roomTypeStr,
                                       String language) {
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } catch (DateTimeParseException | NullPointerException e) {
            return rejected(translations.message("rooms.invalidDates", language));
        }
        String periodError = validatePeriod(startDate, endDate, language);
        if (periodError != null) {
            return rejected(periodError);
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
            String period = period(startDate, endDate, language);
            String what = roomType != null
                    ? translations.message("rooms.ofType", language, Map.of("type", roomTypeName(hotelId, roomType, language)))
                    : translations.message("rooms.any", language);

            if (!(rooms instanceof List<?> roomList) || roomList.isEmpty()) {
                return noResult(translations.message(roomType != null ? "rooms.noneAvailableOfType" : "rooms.noneAvailable",
                        language, Map.of("period", period, "rooms", what)));
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
                    translations.message("rooms.available", language, Map.of("rooms", what, "period", period)),
                    data);
        } catch (HotelBackendException e) {
            return backendError(translations.message("rooms.searchError", language,
                    Map.of("error", backendErrorText(e.getMessage(), language))));
        } catch (TimeoutException | InterruptedException e) {
            return backendTimeout(translations.message("common.backendUnavailable", language));
        }
    }

    // user – влезлият потребител; токенът му отива през Kafka и booking-system взима потребителя от него
    public UiAction createBookings(String hotelId, ChatUser user, String startDateStr, String endDateStr,
                                   List<String> roomIds, String language) {
        if (user == null) {
            return rejected(translations.message("booking.loginRequired", language));
        }
        if (roomIds == null || roomIds.isEmpty()) {
            return rejected(translations.message("booking.noRoomSelected", language));
        }
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } catch (DateTimeParseException | NullPointerException e) {
            return rejected(translations.message("booking.invalidDates", language));
        }
        String periodError = validatePeriod(startDate, endDate, language);
        if (periodError != null) {
            return rejected(periodError);
        }

        try {
            Object bookings = backendClient.request(hotelId, "create_booking", Map.of(
                    "token", user.token(),
                    "roomIds", roomIds,
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));
            String reply = confirmationText(bookings, startDate, endDate, language);
            chatHistoryService.record(hotelId, user, "Резервирай " + roomNumbersText(bookings) + " от "
                    + startDate.format(BG_DATE) + " до " + endDate.format(BG_DATE) + ".", reply);
            return new UiAction(BOOKING_CONFIRMED_ACTION, reply, bookings);
        } catch (HotelBackendException e) {
            return backendError(translations.message("booking.error", language,
                    Map.of("error", backendErrorText(e.getMessage(), language))));
        } catch (TimeoutException | InterruptedException e) {
            // Не знаем дали бекендът я е записал – потребителят трябва да провери
            return backendTimeout(translations.message("booking.timeout", language));
        }
    }

    // Предстоящите потвърдени резервации на потребителя – за картичките с бутон „Откажи“
    public UiAction myBookings(String hotelId, ChatUser user, String language) {
        if (user == null) {
            return rejected(translations.message("myBookings.loginRequired", language));
        }
        try {
            Object bookings = backendClient.request(hotelId, "get_upcoming_bookings", Map.of("token", user.token()));
            if (!(bookings instanceof List<?> list) || list.isEmpty()) {
                return noResult(translations.message("myBookings.none", language));
            }
            return new UiAction(MY_BOOKINGS_ACTION, translations.message("myBookings.list", language),
                    Map.of("bookings", list));
        } catch (HotelBackendException e) {
            return backendError(translations.message("myBookings.error", language,
                    Map.of("error", backendErrorText(e.getMessage(), language))));
        } catch (TimeoutException | InterruptedException e) {
            return backendTimeout(translations.message("common.backendUnavailable", language));
        }
    }

    public UiAction cancelBooking(String hotelId, ChatUser user, String bookingId, String language) {
        if (user == null) {
            return rejected(translations.message("cancel.loginRequired", language));
        }
        if (bookingId == null || bookingId.isBlank()) {
            return rejected(translations.message("cancel.noBookingSelected", language));
        }
        try {
            Object booking = backendClient.request(hotelId, "cancel_booking", Map.of(
                    "token", user.token(),
                    "bookingId", bookingId
            ));
            String description = describeBooking(hotelId, booking, language);
            String reply = translations.message("cancel.done", language, Map.of("booking", description));
            chatHistoryService.record(hotelId, user, "Откажи резервацията " + description + ".", reply);
            return new UiAction(BOOKING_CANCELED_ACTION, reply, booking);
        } catch (HotelBackendException e) {
            return backendError(translations.message("cancel.error", language,
                    Map.of("error", backendErrorText(e.getMessage(), language))));
        } catch (TimeoutException | InterruptedException e) {
            // Не знаем дали бекендът я е отказал – потребителят трябва да провери
            return backendTimeout(translations.message("cancel.timeout", language));
        }
    }

    // „за стая №12 (Двойна стая) от 30.10.2026 до 03.11.2026“
    private String describeBooking(String hotelId, Object booking, String language) {
        if (!(booking instanceof Map<?, ?> map)) {
            return "";
        }
        StringBuilder text = new StringBuilder(translations.message("cancel.bookingDescription", language,
                Map.of("number", String.valueOf(map.get("roomNumber")))));
        if (map.get("roomType") != null) {
            text.append(" (").append(roomTypeName(hotelId, String.valueOf(map.get("roomType")), language)).append(")");
        }
        LocalDate checkIn = parseDateOrNull(map.get("checkInDate"));
        LocalDate checkOut = parseDateOrNull(map.get("checkOutDate"));
        if (checkIn != null && checkOut != null) {
            text.append(" ").append(period(checkIn, checkOut, language));
        }
        return text.toString();
    }

    // Името на типа стая от бекенда на хотела, преведено на езика (translations); без превод – както е дошло
    private String roomTypeName(String hotelId, String code, String language) {
        return translations.translate(roomTypeService.nameOf(hotelId, code), language);
    }

    // „от 30.10.2026 до 03.11.2026“
    private String period(LocalDate startDate, LocalDate endDate, String language) {
        return translations.message("common.period", language,
                Map.of("start", startDate.format(BG_DATE), "end", endDate.format(BG_DATE)));
    }

    private LocalDate parseDateOrNull(Object value) {
        try {
            return value != null ? LocalDate.parse(String.valueOf(value)) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String validatePeriod(LocalDate startDate, LocalDate endDate, String language) {
        if (!startDate.isBefore(endDate)) {
            return translations.message("rooms.checkOutBeforeCheckIn", language);
        }
        if (startDate.isBefore(LocalDate.now())) {
            return translations.message("rooms.checkInInPast", language);
        }
        return null;
    }

    private String confirmationText(Object bookings, LocalDate startDate, LocalDate endDate, String language) {
        StringBuilder text = new StringBuilder(translations.message("booking.confirmed", language,
                Map.of("period", period(startDate, endDate, language))));
        double total = 0;
        if (bookings instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> booking) {
                    Object price = booking.get("totalPrice");
                    text.append("\n").append(translations.message("booking.confirmedRoom", language, Map.of(
                            "number", String.valueOf(booking.get("roomNumber")),
                            "price", String.format("%.2f", toDouble(price)))));
                    total += toDouble(price);
                }
            }
        }
        return text.append("\n").append(translations.message("booking.total", language,
                Map.of("total", String.format("%.2f", total)))).toString();
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

    // booking-system връща причините като кодове на английски (ResponseStatusException reason) –
    // текстът за потребителя е в translations (ползва се и от HotelTools); непознат код – както е дошъл
    public String backendErrorText(String reason, String language) {
        String key = switch (reason == null ? "" : reason) {
            case "Room not available" -> "backendError.roomNotAvailable";
            case "User not found" -> "backendError.userNotFound";
            case "Room not found" -> "backendError.roomNotFound";
            case "Invalid dates" -> "backendError.invalidDates";
            case "Invalid room type" -> "backendError.invalidRoomType";
            case "Booking not found" -> "backendError.bookingNotFound";
            case "Login required", "Invalid token" -> "backendError.loginRequired";
            case "Session expired" -> "backendError.sessionExpired";
            case "Booking is already canceled" -> "backendError.alreadyCanceled";
            case "Cancellation deadline passed" -> "backendError.cancellationDeadlinePassed";
            default -> null;
        };
        return key != null ? translations.message(key, language) : reason;
    }

    private double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    // Само текст, без действие в UI; outcome/errorType са за логовете
    private UiAction rejected(String reply) {
        return new UiAction(null, reply, null, ChatLogEntry.REJECTED, null);
    }

    private UiAction noResult(String reply) {
        return new UiAction(null, reply, null, ChatLogEntry.NO_RESULT, null);
    }

    private UiAction backendError(String reply) {
        return new UiAction(null, reply, null, ChatLogEntry.ERROR, ChatLogEntry.BACKEND_ERROR);
    }

    private UiAction backendTimeout(String reply) {
        return new UiAction(null, reply, null, ChatLogEntry.ERROR, ChatLogEntry.BACKEND_TIMEOUT);
    }
}
