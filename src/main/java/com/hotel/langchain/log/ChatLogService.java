package com.hotel.langchain.log;

import com.hotel.langchain.model.ChatLog;
import com.hotel.langchain.repository.ChatLogRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Структурирани логове в logs_<hotelId> (ChatLogRepository) – за отчетите.
// Отделна заявка (въпрос в чата, бутон със знание) е един запис; действие от няколко
// заявки (нова резервация, отказ) е един запис, който се допълва с всяка стъпка (ChatFlow).
@Service
public class ChatLogService {

    // Колко записа могат да чакат, ако Mongo е бавен; над това новите се изпускат
    private static final int MAX_PENDING = 1_000;

    private final ChatLogRepository chatLogRepository;
    // Една нишка само за логовете: бавен Atlas не заема общия ForkJoinPool и заявките не чакат.
    // При пълна опашка записът се изпуска със съобщение, вместо да хвърли грешка в заявката.
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING),
            runnable -> {
                Thread thread = new Thread(runnable, "chat-log-writer");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, pool) -> System.err.println("Chat log queue is full, dropping a log entry"));

    public ChatLogService(ChatLogRepository chatLogRepository) {
        this.chatLogRepository = chatLogRepository;
    }

    // Асинхронно: записът не забавя отговора, а грешка в него не проваля заявката
    public void log(String hotelId, ChatLogEntry entry) {
        if (hotelId == null || hotelId.isBlank() || entry == null) {
            return;
        }
        ChatLog log = entry.toChatLog(hotelId);
        write(hotelId, () -> chatLogRepository.insert(hotelId, log));
    }

    // Стъпка в действие. Записите вървят в една нишка, затова стъпките на едно действие се записват по ред.
    public void logStep(String hotelId, String flowId, String flowType, String startedBy, String status,
                        ChatLogEntry step) {
        if (hotelId == null || hotelId.isBlank() || flowId == null || step == null) {
            return;
        }
        ChatLog flow = new ChatLog();
        flow.setFlowId(flowId);
        flow.setType(flowType);
        flow.setStartedBy(startedBy);
        flow.setStatus(status);
        flow.setUserId(step.userId());
        ChatLog.Step flowStep = step.toStep();
        write(hotelId, () -> chatLogRepository.addFlowStep(hotelId, flow, flowStep));
    }

    private void write(String hotelId, Runnable action) {
        CompletableFuture.runAsync(action, executor).exceptionally(e -> {
            System.err.println("Could not save chat log for hotelId=" + hotelId + ": " + e);
            return null;
        });
    }

    // При спиране на приложението: дописва чакащите записи (до 5s)
    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            System.err.println("Chat log writer did not finish, pending entries: " + executor.getQueue().size());
        }
    }
}
