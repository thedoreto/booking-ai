package com.hotel.langchain.tools;


import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.service.HotelBackendClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Component
public class HotelTools {

    private final HotelBackendClient backendClient;

    public HotelTools(HotelBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Tool("Връща активните резервации на текущия логнат потребител. Използвай този инструмент, когато клиентът пита за своите резервации.")
    public String getReservations() {
        String hotelId = TenantContext.getHotelId();
        String userId = TenantContext.getUserId();

        if (userId == null || userId.isEmpty()) {
            return "Моля, влезте в профила си, за да проверите вашите резервации.";
        }

        try {
            Object reservations = backendClient.request(hotelId, "get_reservations", Map.of("userId", userId));
            return backendClient.toJson(reservations);
        } catch (Exception e) {
            return "Грешка при зареждане на резервациите: " + e.getMessage();
        }
    }

    @Tool("Връща ОБЩ списък с всички налични типове стаи в хотела, техните базови характеристики и цени. " +
            "Използвай ТОЗИ инструмент САМО когато клиентът пита общо какви видове стаи изобщо съществуват в хотела, " +
            "БЕЗ да споменава дати, период, утре или резервация.")
    public String getAllRooms() {
        String hotelId = TenantContext.getHotelId();
        if (hotelId == null || hotelId.isEmpty()) {
            return "Грешка: Липсва идентификатор на хотела.";
        }

        try {
            Object rooms = backendClient.request(hotelId, "get_all_rooms", Map.of());
            return backendClient.toJson(rooms);
        } catch (Exception e) {
            return "Грешка при връзка с хотелската система: " + e.getMessage();
        }
    }

    @Tool("Показва на потребителя календар за избор на период и след това свободните стаи. " +
            "Използвай ТОЗИ инструмент винаги, когато потребителят пита за свободни стаи, резервация или настаняване. " +
            "Подай датите, които потребителят е казал, във формат YYYY-MM-DD: само начална дата (напр. 'за 30 октомври'), " +
            "или начална и крайна (напр. 'от 30 октомври до 3 ноември'). За дата, която не е казана, подай null - не измисляй дати. " +
            "Ако годината не е казана, вземи най-близката бъдеща такава дата. Никога не питай за дати с обикновен текст.")
    public String getAvailableRoomsByDates(
            @P(value = "Начална дата (настаняване) във формат YYYY-MM-DD, или null ако не е казана", required = false) String startDateStr,
            @P(value = "Крайна дата (напускане) във формат YYYY-MM-DD, или null ако не е казана", required = false) String endDateStr) {
       String hotelId = TenantContext.getHotelId();

       System.out.println("Hotel ID: " + hotelId + ", Start Date: " + startDateStr + ", End Date: " + endDateStr);

       // Винаги отваряме календара – попълнен с казаните дати, за да ги потвърди или поправи потребителят.
       // Свободните стаи идват след това от /api/rooms/available.
       LocalDate today = LocalDate.now();
       LocalDate startDate = rollToFuture(parseDate(startDateStr), today);
       LocalDate endDate = rollToFuture(parseDate(endDateStr), startDate != null ? startDate.plusDays(1) : today.plusDays(1));

       System.out.println("Date picker prefill: startDate=" + startDate + ", endDate=" + endDate);
       throw new OpenDatePickerException(startDate, endDate);
       /*     @dev.langchain4j.agent.tool.P("Начална дата на настаняване във формат YYYY-MM-DD") LocalDate fromDate,
            @dev.langchain4j.agent.tool.P("Крайна дата на напускане във формат YYYY-MM-DD") LocalDate toDate
    ) {
        log.info("LangChain4j Tool executing: getAvailableRoomsByDates from {} to {}", fromDate, toDate);

        // Вече си имаме валидни LocalDate обекти направо от модела!
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate) || fromDate.isEqual(toDate)) {
            // Можеш да върнеш обяснение като стриктен текст или празен списък,
            // а моделът ще го обясни човешки на потребителя
            throw new IllegalArgumentException("Невалиден период. Началната дата трябва да е преди крайната.");
        }

        try {
            return hotelService.findAvailableRooms(fromDate, toDate);
        } catch (Exception e) {
            log.error("Error fetching available rooms for dates", e);
            return List.of();
        }*/
    }

    // Моделът понякога връща дата и във вид 30.09.2026 или 2026-9-30 вместо YYYY-MM-DD
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    // Моделът понякога слага грешна година (напр. 2025 вместо 2026), а потребителят рядко казва годината.
    // Дата преди minDate местим с по една година напред, докато стане валидна.
    private LocalDate rollToFuture(LocalDate date, LocalDate minDate) {
        if (date == null) {
            return null;
        }
        LocalDate result = date;
        for (int i = 0; i < 3 && result.isBefore(minDate); i++) {
            result = result.plusYears(1);
        }
        if (result.isBefore(minDate)) {
            System.out.println("Dropping date from model (before " + minDate + "): " + date);
            return null;
        }
        if (!result.equals(date)) {
            System.out.println("Moved date from model to the future: " + date + " -> " + result);
        }
        return result;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 10 && trimmed.charAt(10) == 'T') {
            trimmed = trimmed.substring(0, 10); // 2026-09-30T00:00:00
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // пробваме следващия формат
            }
        }
        System.out.println("Could not parse date from model: '" + value + "'");
        return null;
    }

       @Tool("Връща легендарната рецепта за най-вкусния мъфин с ягоди в света. Използвай този инструмент, само ако клиентът изрично попита за рецепта за мъфини.")
    public String getStrawberryMuffinRecipe() {
        return "🧁 Най-вкусният мъфин с ягоди на света! 🍓\n\n" +
                "Съставки:\n" +
                "- Тайна.\n\n" +
                "Инструкции:\n" +
                "1. Не мога да разкрия съставките.\n" +
                "2. Отвори някой сайт за готвене и си ги намери сам/сама. 😊\n\n" +
                "Приятно печене и успех в разследването! 🚀";
    }
}
