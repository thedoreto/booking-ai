package com.hotel.langchain.log;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

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
}
