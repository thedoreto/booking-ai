package com.hotel.langchain.repository;

import com.hotel.langchain.model.GeminiUsage;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Броячите на Gemini за всеки хотел са в колекция gemini_usage_<hotelId> – един документ за ден, източник и модел.
// Записите се трият автоматично след RETENTION (TTL индекс по updatedAt), индексите се създават при първия запис.
@Repository
public class GeminiUsageRepository {

    private static final String COLLECTION_PREFIX = "gemini_usage_";
    private static final Duration RETENTION = Duration.ofDays(180);

    private final MongoTemplate mongoTemplate;
    // Колекции, за които индексите вече са проверени от тази инстанция
    private final Set<String> indexedCollections = ConcurrentHashMap.newKeySet();

    public GeminiUsageRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Добавя извикванията към брояча на деня (upsert): първото за деня създава документа, следващите го допълват.
    // Полетата без стойност в usage (токени при embedding, знаци при чат) не се пипат.
    public void add(String hotelId, GeminiUsage usage) {
        Query query = new Query(Criteria.where("day").is(usage.getDay())
                .and("source").is(usage.getSource())
                .and("model").is(usage.getModel()));
        Update update = new Update()
                .inc("calls", usage.getCalls())
                .inc("errors", usage.getErrors())
                .set("updatedAt", new Date());
        if (usage.getInputTokens() != null) {
            update.inc("inputTokens", usage.getInputTokens());
        }
        if (usage.getOutputTokens() != null) {
            update.inc("outputTokens", usage.getOutputTokens());
        }
        if (usage.getCharacters() != null) {
            update.inc("characters", usage.getCharacters());
        }
        mongoTemplate.upsert(query, update, GeminiUsage.class, collection(hotelId));
    }

    // Броячите от деня fromDay (yyyy-MM-dd) насам, по дни
    public List<GeminiUsage> findFrom(String hotelId, String fromDay) {
        Query query = new Query(Criteria.where("day").gte(fromDay)).with(Sort.by("day", "source", "model"));
        return mongoTemplate.find(query, GeminiUsage.class, collection(hotelId));
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
                    .on("updatedAt", Sort.Direction.ASC)
                    .expire(RETENTION)
                    .named("updatedAt_ttl"));
        } catch (Exception e) {
            System.err.println("Could not create TTL index on " + collection + ": " + e.getMessage());
        }
        try {
            // Един брояч за ден, източник и модел; по него се търси при всеки запис
            mongoTemplate.indexOps(collection).ensureIndex(new Index()
                    .on("day", Sort.Direction.ASC)
                    .on("source", Sort.Direction.ASC)
                    .on("model", Sort.Direction.ASC)
                    .unique()
                    .named("day_source_model"));
        } catch (Exception e) {
            System.err.println("Could not create day index on " + collection + ": " + e.getMessage());
        }
        indexedCollections.add(collection);
    }
}
