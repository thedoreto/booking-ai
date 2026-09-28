package com.hotel.langchain.repository;

import com.hotel.langchain.model.ChatLog;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Логовете на всеки хотел са в колекция logs_<hotelId>. Записите се трият автоматично след RETENTION
// (TTL индекс по timestamp), индексите се създават при първия запис в колекцията.
@Repository
public class ChatLogRepository {

    private static final String COLLECTION_PREFIX = "logs_";
    private static final Duration RETENTION = Duration.ofDays(180);

    private final MongoTemplate mongoTemplate;
    // Колекции, за които индексите вече са проверени от тази инстанция
    private final Set<String> indexedCollections = ConcurrentHashMap.newKeySet();

    public ChatLogRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void insert(String hotelId, ChatLog log) {
        mongoTemplate.insert(log, collection(hotelId));
    }

    // Стъпка в действие: първата стъпка създава записа (upsert), следващите го допълват.
    // От flow се взимат flowId и статусът; type, startedBy и userId се записват само от първата стъпка.
    public void addFlowStep(String hotelId, ChatLog flow, ChatLog.Step step) {
        Update update = new Update()
                .setOnInsert("timestamp", step.getAt())
                .setOnInsert("hotelId", hotelId)
                .setOnInsert("userId", flow.getUserId())
                .setOnInsert("type", flow.getType())
                .setOnInsert("startedBy", flow.getStartedBy())
                .set("status", flow.getStatus())
                .set("updatedAt", step.getAt())
                .push("steps", step);
        ChatLog.Gemini gemini = step.getGemini();
        if (gemini != null) {
            update.inc("gemini.calls", gemini.getCalls())
                    .inc("gemini.inputTokens", gemini.getInputTokens())
                    .inc("gemini.outputTokens", gemini.getOutputTokens());
        }
        Query query = new Query(Criteria.where("flowId").is(flow.getFlowId()));
        mongoTemplate.upsert(query, update, ChatLog.class, collection(hotelId));
    }

    private String collection(String hotelId) {
        String collection = COLLECTION_PREFIX + hotelId;
        ensureIndexes(collection);
        return collection;
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
