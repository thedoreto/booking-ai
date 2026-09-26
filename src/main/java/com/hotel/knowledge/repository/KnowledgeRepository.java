package com.hotel.knowledge.repository;

import com.hotel.knowledge.model.KnowledgeDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class KnowledgeRepository {

    private final MongoTemplate mongoTemplate;

    public KnowledgeRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Текстовете на документите по _id, в реда на ids – директно от колекцията, без vector search.
    // Липсващ документ или документ без text се пропуска.
    public List<String> findTextsByIds(List<ObjectId> ids, String collectionName) {
        Query query = new Query(Criteria.where("_id").in(ids));
        Map<Object, String> textById = new HashMap<>();
        for (Document doc : mongoTemplate.find(query, Document.class, collectionName)) {
            textById.put(doc.get("_id"), doc.getString("text"));
        }
        return ids.stream()
                .map(textById::get)
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .toList();
    }

    public List<KnowledgeDocument> searchByVector(
            List<Double> embedding,
            String collectionName) {

        Document vectorSearch = new Document("$vectorSearch",
                new Document("index", "autoembed_index")
                        .append("path", "embedding")
                        .append("queryVector", embedding)
                        .append("numCandidates", 100)
                        .append("limit", 5)
        );

        var aggregation = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                context -> vectorSearch
        );

        return mongoTemplate.aggregate(
                aggregation,
                collectionName,
                KnowledgeDocument.class
        ).getMappedResults();
    }
}