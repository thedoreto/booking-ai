package com.hotel.langchain.tools;


import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.service.HotelBackendClient;
import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import com.hotel.langchain.service.RoomBookingService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
public class HotelTools {

    private final HotelBackendClient backendClient;
    private final RoomBookingService roomBookingService;

    public HotelTools(HotelBackendClient backendClient, RoomBookingService roomBookingService) {
        this.backendClient = backendClient;
        this.roomBookingService = roomBookingService;
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

    @Tool("Връща наличните стаи за период. " +
            "Използвай ТОЗИ инструмент винаги, когато потребителят пита за свободни стаи, резервация или настаняване. " +
            "Ако потребителят не е посочил точни дати (например казва 'за утре' или само пита общо), подай null за липсващите дати. " +
            "Когато клиентът иска да направи резервация, без да е посочил изрично начална И крайна дата, " +
            "ВИНАГИ извикай този инструмент с null за двете дати - никога не питай за дати с обикновен текст и не измисляй дати.")
    public String getAvailableRoomsByDates(String startDateStr, String endDateStr) {
       String hotelId = TenantContext.getHotelId();

       System.out.println("Hotel ID: " + hotelId + ", Start Date: " + startDateStr + ", End Date: " + endDateStr);

       // Без дати или с невалиден период – UI отваря календара
       try {
           LocalDate startDate = LocalDate.parse(startDateStr);
           LocalDate endDate = LocalDate.parse(endDateStr);
           if (!startDate.isBefore(endDate) || startDate.isBefore(LocalDate.now())) {
               throw new OpenDatePickerException();
           }
       } catch (DateTimeParseException | NullPointerException e) {
           throw new OpenDatePickerException();
       }

       TenantContext.UiAction result = roomBookingService.findAvailableRooms(hotelId, startDateStr, endDateStr);
       if (result.actionType() == null) {
           // Няма стаи или грешка – моделът ще го предаде на потребителя
           return result.reply();
       }
       // Списъкът отива директно в UI; следващото извикване на модела се прескача
       TenantContext.requestUiAction(result);
       return result.reply();
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
