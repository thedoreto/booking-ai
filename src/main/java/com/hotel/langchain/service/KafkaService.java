package com.hotel.langchain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class KafkaService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaService(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void send(String topic, String key, Map<String, Object> payload) {
        try {
            String message = objectMapper.writeValueAsString(payload);

            System.out.println("Sending to Kafka: " + message);

            kafkaTemplate.send(topic, key, message).whenComplete((result, ex) -> {
                if (ex != null) {
                    System.err.println("Kafka SEND ERROR: " + ex.getMessage());
                    ex.printStackTrace();
                } else {
                    System.out.println(
                            "Kafka SENT: topic=" + result.getRecordMetadata().topic()
                                    + ", partition=" + result.getRecordMetadata().partition()
                                    + ", offset=" + result.getRecordMetadata().offset()
                                    + ", key=" + key
                    );
                }
            });

        } catch (Exception e) {
            System.err.println("Грешка при изпращане към Kafka: " + e.getMessage());
        }
    }
}