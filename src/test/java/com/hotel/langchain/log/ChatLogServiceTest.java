package com.hotel.langchain.log;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatLogServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);

    @Test
    void writesOnOwnThreadWithoutBlockingAndDropsEntriesWhenQueueIsFull() throws Exception {
        when(mongoTemplate.indexOps(anyString())).thenReturn(mock(IndexOperations.class));
        // Бавен Mongo: първият запис чака, докато тестът не го пусне
        CountDownLatch mongoReleased = new CountDownLatch(1);
        Queue<String> writerThreads = new ConcurrentLinkedQueue<>();
        when(mongoTemplate.insert(any(Map.class), anyString())).thenAnswer(invocation -> {
            writerThreads.add(Thread.currentThread().getName());
            mongoReleased.await();
            return null;
        });
        ChatLogService service = new ChatLogService(mongoTemplate);

        long startNanos = System.nanoTime();
        for (int i = 0; i < 1_100; i++) {
            service.log("seven_stars", ChatLogEntry.start(ChatLogEntry.CHAT, "user-1"));
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        mongoReleased.countDown();
        service.shutdown();

        assertThat(elapsedMs).isLessThan(1_000);
        // 1 записан + 1000 от опашката; останалите 99 са изпуснати без грешка
        verify(mongoTemplate, times(1_001)).insert(any(Map.class), eq("logs_seven_stars"));
        assertThat(writerThreads).isNotEmpty().containsOnly("chat-log-writer");
    }

    @Test
    void skipsEntriesWithoutHotelId() throws Exception {
        ChatLogService service = new ChatLogService(mongoTemplate);

        service.log(null, ChatLogEntry.start(ChatLogEntry.CHAT, "user-1"));
        service.log(" ", ChatLogEntry.start(ChatLogEntry.CHAT, "user-1"));
        service.shutdown();

        verify(mongoTemplate, never()).insert(any(Map.class), anyString());
    }

    @Test
    void upsertsFlowStepByFlowId() throws Exception {
        when(mongoTemplate.indexOps(anyString())).thenReturn(mock(IndexOperations.class));
        ChatLogService service = new ChatLogService(mongoTemplate);

        service.logStep("seven_stars", "flow-1", ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.ROOMS_SHOWN, ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1").detail("roomsFound", 2));
        service.shutdown();

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).upsert(query.capture(), update.capture(), eq("logs_seven_stars"));

        assertThat(query.getValue().getQueryObject()).containsEntry("flowId", "flow-1");
        Document changes = update.getValue().getUpdateObject();
        // Първата стъпка задава вида и началото; всяка стъпка сменя статуса и се добавя към steps
        assertThat(changes.get("$setOnInsert", Document.class))
                .containsEntry("type", "new_booking")
                .containsEntry("startedBy", "button")
                .containsEntry("userId", "user-1")
                .containsEntry("hotelId", "seven_stars")
                .containsKey("timestamp");
        assertThat(changes.get("$set", Document.class))
                .containsEntry("status", "rooms_shown")
                .containsKey("updatedAt");
        @SuppressWarnings("unchecked")
        Map<String, Object> step = (Map<String, Object>) changes.get("$push", Document.class).get("steps");
        assertThat(step).containsEntry("step", "search").containsEntry("roomsFound", 2);
        assertThat(changes).doesNotContainKey("$inc");
    }

    @Test
    void skipsFlowStepWithoutFlowId() throws Exception {
        ChatLogService service = new ChatLogService(mongoTemplate);

        service.logStep("seven_stars", null, ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.ROOMS_SHOWN, ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1"));
        service.shutdown();

        verify(mongoTemplate, never()).upsert(any(Query.class), any(UpdateDefinition.class), anyString());
    }
}
