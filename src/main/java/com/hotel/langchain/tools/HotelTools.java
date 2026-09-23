package com.hotel.langchain.tools;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.langchain.context.TenantContext;
import com.hotel.langchain.exception.OpenDatePickerException;
import com.hotel.langchain.service.KafkaService;
import dev.langchain4j.agent.tool.Tool;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Component
public class HotelTools {

    private final KafkaService kafkaService;

    // Пазим чакащите заявки, за да върнем отговора точно на този инструмент
    private final ConcurrentMap<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();

    private static final String REQUEST_TOPIC = "hotel-requests-topic";
    private static final String REPLY_TOPIC = "hotel-replies-topic";

    public HotelTools(KafkaService kafkaService) {
        this.kafkaService = kafkaService;
    }

    @Tool("Връща активните резервации на текущия логнат потребител. Използвай този инструмент, когато клиентът пита за своите резервации.")
    public String getReservations() {
        String hotelId = TenantContext.getHotelId();
        String userId = TenantContext.getUserId();

        if (userId == null || userId.isEmpty()) {
            return "Моля, влезте в профила си, за да проверите вашите резервации.";
        }

        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        responseFutures.put(correlationId, future);

        try {
            Map<String, Object> message = Map.of(
                    "correlationId", correlationId,
                    "replyTo", REPLY_TOPIC,
                    "hotelId", hotelId,
                    "userId", userId, // Пращаме го към бекенда
                    "event", "get_reservations"
            );

            kafkaService.send(REQUEST_TOPIC, hotelId, message);
            return future.get(5, TimeUnit.SECONDS);

        } catch (Exception e) {
            responseFutures.remove(correlationId);
            return "Грешка при зареждане на резервациите: " + e.getMessage();
        }
    }

    @Tool("Връща ОБЩ списък с всички налични типове стаи в хотела, техните базови характеристики и цени. " +
            "Използвай ТОЗИ инструмент САМО когато клиентът пита общо какви видове стаи изобщо съществуват в хотела, " +
            "БЕЗ да споменава дати, период, утре или резервация.")
    public String getAllRooms() {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        responseFutures.put(correlationId, future);

        String hotelId = TenantContext.getHotelId();
        if (hotelId == null || hotelId.isEmpty()) {
            return "Грешка: Липсва идентификатор на хотела.";
        }

        try {
            // 1. Изпращаме съобщението към другия микросервиз през Kafka с нужните метаданни
            Map<String, Object> message = Map.of(
                    "correlationId", correlationId,
                    "replyTo", REPLY_TOPIC,
                    "hotelId", hotelId,
                    "event", "get_all_rooms"
            );

            kafkaService.send(REQUEST_TOPIC, hotelId, message);
            return future.get(5, TimeUnit.SECONDS);

        } catch (Exception e) {
            responseFutures.remove(correlationId);
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

       if (startDateStr == null || endDateStr == null) {
           throw new OpenDatePickerException();
       }

       String correlationId = UUID.randomUUID().toString();

       try {
           LocalDate startDate = LocalDate.parse(startDateStr);
           LocalDate endDate = LocalDate.parse(endDateStr);
           if (!startDate.isBefore(endDate)) {
               throw new OpenDatePickerException();
           }
           if (startDate.isBefore(LocalDate.now())) {
               throw new OpenDatePickerException();
           }

           CompletableFuture<String> future = new CompletableFuture<>();
           responseFutures.put(correlationId, future);


           Map<String, Object> message = Map.of(
                   "correlationId", correlationId,
                   "replyTo", REPLY_TOPIC,
                   "hotelId", hotelId,
                   "startDate", startDate.toString(),
                   "endDate", endDate.toString(),
                   "event", "get_available_rooms_by_dates"
           );

           kafkaService.send(REQUEST_TOPIC, hotelId, message);

           return future.get(5, TimeUnit.SECONDS);

       } catch (Exception e) {
           responseFutures.remove(correlationId);
           throw new OpenDatePickerException();
       }
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



    @KafkaListener(topics = REPLY_TOPIC, groupId = "agent-tools-reply-group")
    public void listenForReplies(ConsumerRecord<String, String> record) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> responsePayload = objectMapper.readValue(record.value(), Map.class);

            // 1. Взимаме correlationId от върнатия отговор
            String correlationId = (String) responsePayload.get("correlationId");

            // 2. Проверяваме дали пазим такъв чакащ future в паметта
            if (correlationId != null && responseFutures.containsKey(correlationId)) {
                // Взимаме обекта/списъка "data" и го правим на чист JSON string
                Object rawData = responsePayload.get("data");
                String roomData = objectMapper.writeValueAsString(rawData);

                responseFutures.get(correlationId).complete(roomData);
                responseFutures.remove(correlationId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}