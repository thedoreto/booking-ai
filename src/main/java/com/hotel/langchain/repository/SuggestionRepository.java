package com.hotel.langchain.repository;

import com.hotel.langchain.model.SuggestionAnalysis;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Анализите за предложения на всеки хотел са в колекция suggestions_<hotelId>. Трият се автоматично след RETENTION
// (TTL индекс по createdAt), индексът се създава при първото ползване на колекцията.
@Repository
public class SuggestionRepository {

    private static final String COLLECTION_PREFIX = "suggestions_";
    private static final Duration RETENTION = Duration.ofDays(180);

    private final MongoTemplate mongoTemplate;
    // Колекции, за които индексът вече е проверен от тази инстанция
    private final Set<String> indexedCollections = ConcurrentHashMap.newKeySet();

    public SuggestionRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public SuggestionAnalysis insert(String hotelId, SuggestionAnalysis analysis) {
        return mongoTemplate.insert(analysis, collection(hotelId));
    }

    // Последният анализ
    public Optional<SuggestionAnalysis> findLatest(String hotelId) {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(1);
        return Optional.ofNullable(mongoTemplate.findOne(query, SuggestionAnalysis.class, collection(hotelId)));
    }

    // Одобрените и отхвърлените предложения от всички анализи (най-новите първи, най-много limit) – за да не се
    // предлагат пак. Връщат се type, topic и label.
    public List<SuggestionAnalysis.Item> findHandled(String hotelId, int limit) {
        List<Document> pipeline = List.of(
                new Document("$unwind", "$items"),
                new Document("$match", new Document("items.status",
                        new Document("$in", List.of(SuggestionAnalysis.ACCEPTED, SuggestionAnalysis.DISMISSED)))),
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$limit", limit),
                new Document("$project", new Document("_id", 0).append("type", "$items.type")
                        .append("status", "$items.status").append("topic", "$items.topic").append("label", "$items.label")));
        List<SuggestionAnalysis.Item> handled = new ArrayList<>();
        for (Document d : mongoTemplate.getCollection(collection(hotelId)).aggregate(pipeline)) {
            SuggestionAnalysis.Item item = new SuggestionAnalysis.Item();
            item.setType(d.getString("type"));
            item.setStatus(d.getString("status"));
            item.setTopic(d.getString("topic"));
            item.setLabel(d.getString("label"));
            handled.add(item);
        }
        return handled;
    }

    // Сменя статуса на едно предложение; false – няма такъв анализ или предложение
    public boolean updateItemStatus(String hotelId, String analysisId, String itemId, String status) {
        Query query = new Query(Criteria.where("_id").is(analysisId).and("items.id").is(itemId));
        return mongoTemplate.updateFirst(query, new Update().set("items.$.status", status), SuggestionAnalysis.class,
                collection(hotelId)).getMatchedCount() > 0;
    }

    private String collection(String hotelId) {
        String collection = COLLECTION_PREFIX + hotelId;
        if (indexedCollections.add(collection)) {
            try {
                mongoTemplate.indexOps(collection).ensureIndex(new Index()
                        .on("createdAt", Sort.Direction.ASC)
                        .expire(RETENTION)
                        .named("createdAt_ttl"));
            } catch (Exception e) {
                indexedCollections.remove(collection);
                System.err.println("Could not create TTL index on " + collection + ": " + e.getMessage());
            }
        }
        return collection;
    }
}
