package com.hotel.knowledge.repository;

import com.hotel.knowledge.model.KnowledgeDocument;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeRepository
        extends MongoRepository<KnowledgeDocument, String> {

    @Aggregation(pipeline = {
            """
            {
              "$vectorSearch": {
                "index": "autoembed_index",
                "path": "embedding",
                "queryVector": ?0,
                "numCandidates": 100,
                "limit": 5
              }
            }
            """
    })
    List<KnowledgeDocument> searchByVector(List<Double> embedding);

}
