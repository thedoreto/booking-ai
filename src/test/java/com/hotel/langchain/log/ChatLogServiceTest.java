package com.hotel.langchain.log;

import com.hotel.langchain.model.ChatLog;
import com.hotel.langchain.repository.ChatLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ChatLogServiceTest {

    private final ChatLogRepository repository = mock(ChatLogRepository.class);

    @Test
    void writesOnOwnThreadWithoutBlockingAndDropsEntriesWhenQueueIsFull() throws Exception {
        // Бавен Mongo: първият запис чака, докато тестът не го пусне
        CountDownLatch mongoReleased = new CountDownLatch(1);
        Queue<String> writerThreads = new ConcurrentLinkedQueue<>();
        doAnswer(invocation -> {
            writerThreads.add(Thread.currentThread().getName());
            mongoReleased.await();
            return null;
        }).when(repository).insert(anyString(), any(ChatLog.class));
        ChatLogService service = new ChatLogService(repository);

        long startNanos = System.nanoTime();
        for (int i = 0; i < 1_100; i++) {
            service.log("seven_stars", ChatLogEntry.start(ChatLogEntry.CHAT, "user-1"));
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        mongoReleased.countDown();
        service.shutdown();

        assertThat(elapsedMs).isLessThan(1_000);
        // 1 записан + 1000 от опашката; останалите 99 са изпуснати без грешка
        verify(repository, times(1_001)).insert(eq("seven_stars"), any(ChatLog.class));
        assertThat(writerThreads).isNotEmpty().containsOnly("chat-log-writer");
    }

    @Test
    void skipsEntriesWithoutHotelId() throws Exception {
        ChatLogService service = new ChatLogService(repository);

        service.log(null, ChatLogEntry.start(ChatLogEntry.CHAT, "user-1"));
        service.log(" ", ChatLogEntry.start(ChatLogEntry.CHAT, "user-1"));
        service.shutdown();

        verify(repository, never()).insert(anyString(), any(ChatLog.class));
    }

    @Test
    void flowStepCarriesFlowAndStep() throws Exception {
        ChatLogService service = new ChatLogService(repository);

        service.logStep("seven_stars", "flow-1", ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.ROOMS_SHOWN, ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1").detail("roomsFound", 2));
        service.shutdown();

        ArgumentCaptor<ChatLog> flow = ArgumentCaptor.forClass(ChatLog.class);
        ArgumentCaptor<ChatLog.Step> step = ArgumentCaptor.forClass(ChatLog.Step.class);
        verify(repository).addFlowStep(eq("seven_stars"), flow.capture(), step.capture());
        assertThat(flow.getValue().getFlowId()).isEqualTo("flow-1");
        assertThat(flow.getValue().getType()).isEqualTo("new_booking");
        assertThat(flow.getValue().getStartedBy()).isEqualTo("button");
        assertThat(flow.getValue().getStatus()).isEqualTo("rooms_shown");
        assertThat(flow.getValue().getUserId()).isEqualTo("user-1");
        assertThat(step.getValue().getStep()).isEqualTo("search");
        assertThat(step.getValue().getDetails()).containsEntry("roomsFound", 2);
    }

    @Test
    void skipsFlowStepWithoutFlowId() throws Exception {
        ChatLogService service = new ChatLogService(repository);

        service.logStep("seven_stars", null, ChatFlow.NEW_BOOKING, ChatFlow.STARTED_BY_BUTTON,
                ChatFlow.ROOMS_SHOWN, ChatLogEntry.start(ChatLogEntry.SEARCH, "user-1"));
        service.shutdown();

        verify(repository, never()).addFlowStep(anyString(), any(ChatLog.class), any(ChatLog.Step.class));
    }
}
