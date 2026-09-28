package com.hotel.langchain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Request/reply към booking-system през Kafka. Всеки хотел има свои топици: заявките – в hotel-requests-<hotelId>,
// отговорите – в hotel-replies-<hotelId> (бекендът на хотела пише само в своя). Отговорът се свързва със заявката
// по correlationId и се приема само от топика на хотела на заявката.
@Service
public class HotelBackendClient {

    private static final String REQUEST_TOPIC_PREFIX = "hotel-requests-";
    private static final String REPLY_TOPIC_PREFIX = "hotel-replies-";
    private static final long TIMEOUT_SECONDS = 5;

    // Чакаща заявка: хотелът (за проверка на топика на отговора) и future-ът, който чака отговора
    private record Pending(String hotelId, CompletableFuture<Object> future) {}

    private final KafkaService kafkaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Пазим чакащите заявки, за да върнем отговора точно на този, който го чака
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    public HotelBackendClient(KafkaService kafkaService) {
        this.kafkaService = kafkaService;
    }

    // Връща полето "data" от отговора (списък/обект, парснат от JSON).
    // При "error" в отговора хвърля HotelBackendException със съобщението от бекенда.
    public Object request(String hotelId, String event, Map<String, Object> params)
            throws HotelBackendException, TimeoutException, InterruptedException {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<Object> future = new CompletableFuture<>();
        pending.put(correlationId, new Pending(hotelId, future));

        Map<String, Object> message = new HashMap<>(params);
        message.put("correlationId", correlationId);
        message.put("hotelId", hotelId);
        message.put("event", event);

        try {
            kafkaService.send(REQUEST_TOPIC_PREFIX + hotelId, hotelId, message);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HotelBackendException backendException) {
                throw backendException;
            }
            // Не се очаква: future-ът се проваля само с HotelBackendException
            throw new IllegalStateException("Unexpected error for event " + event, e.getCause());
        } finally {
            pending.remove(correlationId);
        }
    }

    public String toJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }

    // Отговорите на всички хотели (hotel-replies-*). Уникална група за всяка инстанция: отговорът трябва да стигне
    // до инстанцията, която чака future-а. metadata.max.age.ms – нов хотел (нов топик) се вижда до 30s, не до 5 мин.
    @KafkaListener(topicPattern = REPLY_TOPIC_PREFIX + ".*", groupId = "agent-tools-reply-#{T(java.util.UUID).randomUUID()}",
            properties = "metadata.max.age.ms=30000")
    public void listenForReplies(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> responsePayload = objectMapper.readValue(record.value(), Map.class);

            String correlationId = (String) responsePayload.get("correlationId");
            Pending request = correlationId == null ? null : pending.get(correlationId);
            if (request == null) {
                return; // отговор за друга инстанция или вече изтекла заявка
            }
            if (!record.topic().equals(REPLY_TOPIC_PREFIX + request.hotelId())) {
                // Отговор на заявка на друг хотел – приема се само от топика на хотела на заявката
                System.err.println("Ignoring reply from " + record.topic() + " for a request to hotelId=" + request.hotelId());
                return;
            }
            CompletableFuture<Object> future = request.future();

            Object error = responsePayload.get("error");
            if (error != null) {
                future.completeExceptionally(new HotelBackendException(error.toString()));
            } else {
                future.complete(responsePayload.get("data"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static class HotelBackendException extends Exception {
        public HotelBackendException(String message) {
            super(message);
        }
    }
}
