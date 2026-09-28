package com.hotel.knowledge.repository;

import com.hotel.knowledge.model.KnowledgeDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class KnowledgeRepository {

    // Знанията на всеки хотел са в колекция knowledge_<hotelId>
    private static final String COLLECTION_PREFIX = "knowledge_";

    private final MongoTemplate mongoTemplate;

    public KnowledgeRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Хотелите, които имат знания (колекция knowledge_<hotelId>)
    public Set<String> findHotelIds() {
        return mongoTemplate.getCollectionNames().stream()
                .filter(name -> name.startsWith(COLLECTION_PREFIX))
                .map(name -> name.substring(COLLECTION_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    // Документите по _id, в реда на ids – директно от колекцията, без vector search. Липсващ документ се пропуска.
    // Без embedding – не е нужен, а е голям.
    public List<KnowledgeDocument> findByIds(String hotelId, List<ObjectId> ids) {
        Query query = new Query(Criteria.where("_id").in(ids));
        query.fields().exclude("embedding");
        Map<String, KnowledgeDocument> byId = mongoTemplate.find(query, KnowledgeDocument.class, collection(hotelId))
                .stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));
        return ids.stream()
                .map(id -> byId.get(id.toHexString()))
                .filter(Objects::nonNull)
                .toList();
    }

    public List<KnowledgeDocument> searchByVector(String hotelId, List<Double> embedding) {
        Document vectorSearch = new Document("$vectorSearch",
                new Document("index", "autoembed_index")
                        .append("path", "embedding")
                        .append("queryVector", embedding)
                        .append("numCandidates", 100)
                        .append("limit", 5)
        );

        Aggregation aggregation = Aggregation.newAggregation(context -> vectorSearch);

        return mongoTemplate.aggregate(aggregation, collection(hotelId), KnowledgeDocument.class).getMappedResults();
    }

    private static String collection(String hotelId) {
        return COLLECTION_PREFIX + hotelId;
    }
}
