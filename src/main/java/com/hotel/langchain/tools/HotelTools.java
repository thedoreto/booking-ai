package com.hotel.langchain.tools;


import com.hotel.langchain.context.ChatUser;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.service.HotelBackendClient;
import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import com.hotel.langchain.service.RoomBookingService;
import com.hotel.langchain.service.RoomTypeService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Component
public class HotelTools {

    private final HotelBackendClient backendClient;
    private final RoomTypeService roomTypeService;
    private final RoomBookingService roomBookingService;

    public HotelTools(HotelBackendClient backendClient, RoomTypeService roomTypeService,
                      RoomBookingService roomBookingService) {
        this.backendClient = backendClient;
        this.roomTypeService = roomTypeService;
        this.roomBookingService = roomBookingService;
    }

    @Tool("Показва на потребителя предстоящите му резервации като картички с бутон „Откажи“. " +
            "Използвай ТОЗИ инструмент, когато потребителят иска да откаже или анулира резервация, " +
            "или иска да види списък с резервациите си. Ти НЕ отказваш резервации – потребителят го прави с бутона.")
    public String showMyBookings() {
        // Отговорът отива директно в UI (без второ извикване към Gemini) – виж UiActionShortCircuitChatModel
        TenantContext.UiAction action = roomBookingService.myBookings(TenantContext.getHotelId(), TenantContext.getUser());
        TenantContext.requestUiAction(action);
        return action.reply();
    }

    @Tool("Връща резервациите на текущия логнат потребител като данни. Използвай този инструмент, когато клиентът задава въпрос " +
            "за резервациите си (напр. кога е настаняването или колко струва). За списък с резервации или отказ използвай showMyBookings.")
    public String getReservations() {
        String hotelId = TenantContext.getHotelId();
        ChatUser user = TenantContext.getUser();

        if (user == null) {
            return "Моля, влезте в профила си, за да проверите вашите резервации.";
        }

        try {
            Object reservations = backendClient.request(hotelId, "get_reservations", Map.of("token", user.token()));
            return backendClient.toJson(reservations);
        } catch (Exception e) {
            return toolError("Грешка при зареждане на резервациите: ", e);
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
            return toolError("Грешка при зареждане на стаите: ", e);
        }
    }

    @Tool("Връща типовете стаи в хотела (само имената им). Използвай този инструмент, когато клиентът пита " +
            "какви типове стаи има хотелът. Работи и без вход.")
    public String getRoomTypes() {
        // Типовете идват от booking-system през Kafka (event get_room_types), кеширани в RoomTypeService
        List<RoomTypeService.RoomType> types = roomTypeService.getRoomTypes(TenantContext.getHotelId());
        if (types.isEmpty()) {
            return "В момента няма информация за типовете стаи. Моля, опитайте отново след малко.";
        }
        return "Типове стаи в хотела: "
                + types.stream().map(RoomTypeService.RoomType::name).collect(Collectors.joining(", ")) + ".";
    }

    @Tool("Показва на потребителя календар за избор на период и след това свободните стаи. " +
            "Използвай ТОЗИ инструмент винаги, когато потребителят пита за свободни стаи, резервация или настаняване. " +
            "Подай датите, които потребителят е казал, във формат YYYY-MM-DD: само начална дата (напр. 'за 30 октомври'), " +
            "или начална и крайна (напр. 'от 30 октомври до 3 ноември'). За дата, която не е казана, подай null - не измисляй дати. " +
            "Ако годината не е казана, вземи най-близката бъдеща такава дата. Никога не питай за дати с обикновен текст. " +
            "Ако потребителят е казал тип стая, подай кода му от списъка с типове стаи в системните инструкции; иначе null.")
    public String getAvailableRoomsByDates(
            @P(value = "Начална дата (настаняване) във формат YYYY-MM-DD, или null ако не е казана", required = false) String startDateStr,
            @P(value = "Крайна дата (напускане) във формат YYYY-MM-DD, или null ако не е казана", required = false) String endDateStr,
            @P(value = "Код на тип стая (напр. DOUBLE), или null ако не е казан", required = false) String roomType) {
       String hotelId = TenantContext.getHotelId();

       System.out.println("Hotel ID: " + hotelId + ", Start Date: " + startDateStr + ", End Date: " + endDateStr);

       // Винаги отваряме календара – попълнен с казаните дати, за да ги потвърди или поправи потребителят.
       // Свободните стаи идват след това от /api/rooms/available.
       LocalDate today = LocalDate.now();
       LocalDate startDate = rollToFuture(parseDate(startDateStr), today);
       LocalDate endDate = rollToFuture(parseDate(endDateStr), startDate != null ? startDate.plusDays(1) : today.plusDays(1));

       String type = roomTypeService.normalize(hotelId, roomType);

       System.out.println("Date picker prefill: startDate=" + startDate + ", endDate=" + endDate + ", roomType=" + type);
       throw new OpenDatePickerException(startDate, endDate, type);
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

    // Текст за модела при грешка от бекенда; записва и вида на грешката за логовете на чата
    private String toolError(String prefix, Exception e) {
        if (e instanceof HotelBackendException) {
            TenantContext.reportToolError(ChatLogEntry.BACKEND_ERROR);
            return prefix + RoomBookingService.translateBackendError(e.getMessage());
        }
        if (e instanceof TimeoutException || e instanceof InterruptedException) {
            TenantContext.reportToolError(ChatLogEntry.BACKEND_TIMEOUT);
            return RoomBookingService.BACKEND_UNAVAILABLE;
        }
        System.err.println("Tool failed: " + e);
        TenantContext.reportToolError(ChatLogEntry.INTERNAL);
        return prefix + "техническа грешка.";
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
