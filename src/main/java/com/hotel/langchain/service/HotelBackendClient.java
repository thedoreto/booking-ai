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

// Request/reply към booking-system през Kafka: праща event с correlationId и чака отговора в hotel-replies-topic
@Service
public class HotelBackendClient {

    private static final String REQUEST_TOPIC = "hotel-requests-topic";
    private static final String REPLY_TOPIC = "hotel-replies-topic";
    private static final long TIMEOUT_SECONDS = 5;

    private final KafkaService kafkaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Пазим чакащите заявки, за да върнем отговора точно на този, който го чака
    private final ConcurrentMap<String, CompletableFuture<Object>> responseFutures = new ConcurrentHashMap<>();

    public HotelBackendClient(KafkaService kafkaService) {
        this.kafkaService = kafkaService;
    }

    // Връща полето "data" от отговора (списък/обект, парснат от JSON).
    // При "error" в отговора хвърля HotelBackendException със съобщението от бекенда.
    public Object request(String hotelId, String event, Map<String, Object> params)
            throws HotelBackendException, TimeoutException, InterruptedException {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<Object> future = new CompletableFuture<>();
        responseFutures.put(correlationId, future);

        Map<String, Object> message = new HashMap<>(params);
        message.put("correlationId", correlationId);
        message.put("replyTo", REPLY_TOPIC);
        message.put("hotelId", hotelId);
        message.put("event", event);

        try {
            kafkaService.send(REQUEST_TOPIC, hotelId, message);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (HotelBackendException) e.getCause();
        } finally {
            responseFutures.remove(correlationId);
        }
    }

    public String toJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }

    // Уникална група за всяка инстанция: отговорът трябва да стигне до инстанцията, която чака future-а
    @KafkaListener(topics = REPLY_TOPIC, groupId = "agent-tools-reply-#{T(java.util.UUID).randomUUID()}")
    public void listenForReplies(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> responsePayload = objectMapper.readValue(record.value(), Map.class);

            String correlationId = (String) responsePayload.get("correlationId");
            CompletableFuture<Object> future = correlationId == null ? null : responseFutures.get(correlationId);
            if (future == null) {
                return; // отговор за друга инстанция или вече изтекла заявка
            }

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
