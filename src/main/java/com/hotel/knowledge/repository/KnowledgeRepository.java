package com.hotel.knowledge.repository;

import com.hotel.knowledge.model.KnowledgeDocument;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class KnowledgeRepository {

    private final MongoTemplate mongoTemplate;

    public KnowledgeRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
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