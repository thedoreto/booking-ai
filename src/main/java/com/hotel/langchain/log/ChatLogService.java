package com.hotel.langchain.log;

import jakarta.annotation.PreDestroy;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Структурирани логове на действията в чата в logs_<hotelId> (виж ChatLogEntry) – за отчетите.
// Пише се директно в Mongo. Записите се трият автоматично след RETENTION (TTL индекс по timestamp).
@Service
public class ChatLogService {

    private static final Duration RETENTION = Duration.ofDays(180);
    // Колко записа могат да чакат, ако Mongo е бавен; над това новите се изпускат
    private static final int MAX_PENDING = 1_000;

    private final MongoTemplate mongoTemplate;
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
    // Колекции, за които TTL индексът вече е проверен от тази инстанция
    private final Set<String> indexedCollections = ConcurrentHashMap.newKeySet();

    public ChatLogService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Асинхронно: записът не забавя отговора, а грешка в него не проваля заявката
    public void log(String hotelId, ChatLogEntry entry) {
        if (hotelId == null || hotelId.isBlank() || entry == null) {
            return;
        }
        Map<String, Object> doc = entry.toDocument(hotelId);
        String collection = "logs_" + hotelId;
        CompletableFuture.runAsync(() -> {
            ensureTtlIndex(collection);
            mongoTemplate.insert(doc, collection);
        }, executor).exceptionally(e -> {
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

    private void ensureTtlIndex(String collection) {
        if (indexedCollections.contains(collection)) {
            return;
        }
        try {
            mongoTemplate.indexOps(collection).ensureIndex(new Index()
                    .on("timestamp", Sort.Direction.ASC)
                    .expire(RETENTION)
                    .named("timestamp_ttl"));
        } catch (Exception e) {
            // Напр. вече има индекс по timestamp с други настройки – логът пак се записва
            System.err.println("Could not create TTL index on " + collection + ": " + e.getMessage());
        }
        indexedCollections.add(collection);
    }
}
