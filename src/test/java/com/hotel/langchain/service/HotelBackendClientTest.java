package com.hotel.langchain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HotelBackendClientTest {

    private final KafkaService kafkaService = mock(KafkaService.class);
    private final HotelBackendClient client = new HotelBackendClient(kafkaService);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsTheEventAndReturnsTheDataOfItsReply() throws Exception {
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        // Бекендът отговаря веднага: първо на чужда заявка, после на нашата
        replyWith(message -> {
            sent.set(message);
            reply(Map.of("correlationId", "someone-else", "data", List.of("чужд")));
            reply(Map.of("correlationId", message.get("correlationId"), "data", List.of(Map.of("code", "SINGLE"))));
        });

        Object data = client.request("seven_stars", "get_room_types", Map.of("token", "t-1"));

        assertThat(data).isEqualTo(List.of(Map.of("code", "SINGLE")));
        assertThat(sent.get())
                .containsEntry("hotelId", "seven_stars")
                .containsEntry("event", "get_room_types")
                .containsEntry("token", "t-1")
                .containsEntry("replyTo", "hotel-replies-topic")
                .containsKey("correlationId");
        verify(kafkaService).send(eq("hotel-requests-topic"), eq("seven_stars"), any());
    }

    @Test
    void errorInTheReplyBecomesHotelBackendException() {
        replyWith(message -> reply(Map.of("correlationId", message.get("correlationId"), "error", "Room not available")));

        assertThatThrownBy(() -> client.request("seven_stars", "create_booking", Map.of()))
                .isInstanceOf(HotelBackendException.class)
                .hasMessage("Room not available");
    }

    @Test
    void withoutReplyTheRequestTimesOutAndALateReplyIsIgnored() {
        AtomicReference<Object> correlationId = new AtomicReference<>();
        replyWith(message -> correlationId.set(message.get("correlationId")));

        // Чака TIMEOUT_SECONDS (5s)
        assertThatThrownBy(() -> client.request("seven_stars", "cancel_booking", Map.of()))
                .isInstanceOf(TimeoutException.class);

        // Късен отговор не хвърля и не пречи
        reply(Map.of("correlationId", correlationId.get(), "data", "late"));
    }

    @Test
    void brokenReplyIsSkipped() {
        client.listenForReplies(new ConsumerRecord<>("hotel-replies-topic", 0, 0, null, "not json"));
        reply(Map.of("data", "без correlationId"));
    }

    @SuppressWarnings("unchecked")
    private void replyWith(java.util.function.Consumer<Map<String, Object>> backend) {
        doAnswer(call -> {
            backend.accept(new HashMap<>((Map<String, Object>) call.getArgument(2)));
            return null;
        }).when(kafkaService).send(anyString(), anyString(), any());
    }

    private void reply(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            client.listenForReplies(new ConsumerRecord<>("hotel-replies-topic", 0, 0, null, json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
