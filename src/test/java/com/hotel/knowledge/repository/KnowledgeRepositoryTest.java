package com.hotel.knowledge.repository;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRepositoryTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final KnowledgeRepository repository = new KnowledgeRepository(mongoTemplate);

    @Test
    void textsComeInTheOrderOfTheButtonAndMissingOnesAreSkipped() {
        ObjectId first = new ObjectId();
        ObjectId second = new ObjectId();
        ObjectId missing = new ObjectId();
        ObjectId empty = new ObjectId();
        // Mongo връща документите в свой ред
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("knowledge_seven_stars"))).thenReturn(List.of(
                new Document("_id", empty).append("text", " "),
                new Document("_id", second).append("text", "Втори"),
                new Document("_id", first).append("text", "Първи")));

        List<String> texts = repository.findTextsByIds(List.of(first, missing, second, empty), "knowledge_seven_stars");

        assertThat(texts).containsExactly("Първи", "Втори");
    }
}
