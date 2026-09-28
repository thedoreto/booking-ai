package com.hotel.langchain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaServiceTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void payloadGoesAsJsonWithTheHotelAsKey() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        KafkaService service = new KafkaService(kafkaTemplate, objectMapper);

        service.send("hotel-requests-seven_stars", "seven_stars", Map.of("event", "get_room_types", "hotelId", "seven_stars"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("hotel-requests-seven_stars"), eq("seven_stars"), json.capture());
        assertThat(objectMapper.readValue(json.getValue(), Map.class))
                .isEqualTo(Map.of("event", "get_room_types", "hotelId", "seven_stars"));
    }

    @Test
    void failedSendDoesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        KafkaService service = new KafkaService(kafkaTemplate, objectMapper);

        // Заявката после изтича (timeout в HotelBackendClient), но send не хвърля
        assertThatCode(() -> service.send("hotel-requests-seven_stars", "seven_stars", Map.of("event", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    void payloadThatIsNotJsonIsNotSent() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("not json") {});
        KafkaService service = new KafkaService(kafkaTemplate, broken);

        assertThatCode(() -> service.send("hotel-requests-seven_stars", "seven_stars", Map.of("event", "x")))
                .doesNotThrowAnyException();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
