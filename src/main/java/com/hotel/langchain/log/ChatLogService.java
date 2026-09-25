package com.hotel.langchain.log;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

// Структурирани логове на действията в чата в logs_<hotelId> (виж ChatLogEntry) – за отчетите.
// Пише се директно в Mongo. Записите се трият автоматично след RETENTION (TTL индекс по timestamp).
@Service
public class ChatLogService {

    private static final Duration RETENTION = Duration.ofDays(180);

    private final MongoTemplate mongoTemplate;
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
        }).exceptionally(e -> {
            System.err.println("Could not save chat log for hotelId=" + hotelId + ": " + e);
            return null;
        });
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
