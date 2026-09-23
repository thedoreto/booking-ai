package com.hotel.langchain.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class KafkaMessageConsumer {

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public KafkaMessageConsumer(MongoTemplate mongoTemplate,
                                ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topicPattern = ".*", groupId = "hotel-ai-group")
    public void listen(String message) {

        System.out.println("Получено съобщение от Kafka: " + message);

        try {
            JsonNode json = objectMapper.readTree(message);
            String hotelId = json.get("hotelId").asText();
            Map<String, Object> log = new HashMap<>();

            log.put("timestamp", Instant.now());
            log.put("hotelId", hotelId);
            log.put("event", json.get("event").asText());

            String collectionName = "logs_" + hotelId;
            mongoTemplate.insert(log, collectionName);

            System.out.println("Log saved to MongoDB: " + collectionName);

        } catch (Exception e) {
            System.err.println("Error processing Kafka message: " + e.getMessage());
        }
    }
}