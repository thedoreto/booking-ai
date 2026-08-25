package com.hotel.langchain.tools;


import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class HotelTools {

 /*   private static final Logger log = LoggerFactory.getLogger(HotelTools.class);
    private final HotelService hotelService;

    public HotelTools(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    @Tool("Връща активните резервации на текущия логнат потребител. Използвай този инструмент, когато клиентът пита за своите резервации.")
    public List<BookingDTO> getReservations() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("LangChain4j Tool executing: getReservations for user: {}", auth != null ? auth.getName() : "unknown");

        try {
            return hotelService.getActiveBookings(auth);
        } catch (Exception e) {
            log.error("Error fetching active bookings", e);
            return List.of(); // Връщаме празен списък при грешка, за да не гръмне AI моделът
        }
    }

    @Tool("Връща списък с всички стаи в хотела, техните характеристики и цени. Използвай този инструмент, когато клиентът пита какви стаи има или се интересува от настаняване.")
    public List<RoomDTO> getAllRooms() {
        log.info("LangChain4j Tool executing: getAllRooms");

        try {
            return hotelService.getAllRooms();
        } catch (Exception e) {
            log.error("Error fetching rooms", e);
            return List.of(); // Празен списък при евентуална грешка
        }
    }

    @Tool("Връща наличните стаи за посочен период (начална и крайна дата). " +
            "Използвай този инструмент, когато клиентът търси стаи за конкретни дати. " +
            "Датите задължително трябва да са в бъдещето, а началната дата трябва да е преди крайната.")
    public List<RoomDTO> getAvailableRoomsByDates(
            @dev.langchain4j.agent.tool.P("Начална дата на настаняване във формат YYYY-MM-DD") LocalDate fromDate,
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
        }
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

  */
}