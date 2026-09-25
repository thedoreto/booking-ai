package com.hotel.langchain.log;

import jakarta.annotation.PreDestroy;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// Структурирани логове в logs_<hotelId> – за отчетите. Пише се директно в Mongo.
// Отделна заявка (въпрос в чата, бутон със знание) е един запис (ChatLogEntry); действие от няколко
// заявки (нова резервация, отказ) е един запис, който се допълва с всяка стъпка (ChatFlow).
// Записите се трият автоматично след RETENTION (TTL индекс по timestamp).
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
        write(hotelId, collection -> mongoTemplate.insert(doc, collection));
    }

    // Стъпка в действие: първата стъпка създава записа (upsert), следващите го допълват.
    // Записите вървят в една нишка, затова стъпките на едно действие се записват по ред.
    // flowType, startedBy и userId се записват само от първата стъпка.
    public void logStep(String hotelId, String flowId, String flowType, String startedBy, String status,
                        ChatLogEntry step) {
        if (hotelId == null || hotelId.isBlank() || flowId == null || step == null) {
            return;
        }
        Update update = new Update()
                .setOnInsert("timestamp", step.timestamp())
                .setOnInsert("hotelId", hotelId)
                .setOnInsert("userId", step.userId())
                .setOnInsert("type", flowType)
                .setOnInsert("startedBy", startedBy)
                .set("status", status)
                .set("updatedAt", step.timestamp())
                .push("steps", step.toStepDocument());
        Map<String, Object> gemini = step.gemini();
        if (gemini != null) {
            update.inc("gemini.calls", (Integer) gemini.get("calls"))
                    .inc("gemini.inputTokens", (Integer) gemini.get("inputTokens"))
                    .inc("gemini.outputTokens", (Integer) gemini.get("outputTokens"));
        }
        Query query = new Query(Criteria.where("flowId").is(flowId));
        write(hotelId, collection -> mongoTemplate.upsert(query, update, collection));
    }

    private void write(String hotelId, Consumer<String> action) {
        String collection = "logs_" + hotelId;
        CompletableFuture.runAsync(() -> {
            ensureIndexes(collection);
            action.accept(collection);
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

    private void ensureIndexes(String collection) {
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
        try {
            // Всяка стъпка търси записа на действието по flowId
            mongoTemplate.indexOps(collection).ensureIndex(new Index()
                    .on("flowId", Sort.Direction.ASC)
                    .sparse()
                    .named("flowId"));
        } catch (Exception e) {
            System.err.println("Could not create flowId index on " + collection + ": " + e.getMessage());
        }
        indexedCollections.add(collection);
    }
}
