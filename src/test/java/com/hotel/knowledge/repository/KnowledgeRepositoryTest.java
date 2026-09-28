package com.hotel.knowledge.repository;

import com.hotel.knowledge.model.KnowledgeDocument;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRepositoryTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final KnowledgeRepository repository = new KnowledgeRepository(mongoTemplate);

    @Test
    void documentsComeInTheOrderOfTheButtonAndMissingOnesAreSkipped() {
        ObjectId first = new ObjectId();
        ObjectId second = new ObjectId();
        ObjectId missing = new ObjectId();
        // Mongo връща документите в свой ред
        when(mongoTemplate.find(any(Query.class), eq(KnowledgeDocument.class), eq("knowledge_seven_stars")))
                .thenReturn(List.of(document(second, "Втори"), document(first, "Първи")));

        List<KnowledgeDocument> documents = repository.findByIds("seven_stars", List.of(first, missing, second));

        assertThat(documents).extracting(KnowledgeDocument::getText).containsExactly("Първи", "Втори");
    }

    @Test
    void hotelsAreTheKnowledgeCollections() {
        when(mongoTemplate.getCollectionNames())
                .thenReturn(Set.of("knowledge_seven_stars", "shortcuts_seven_stars", "logs_fake", "shortcuts_other"));

        assertThat(repository.findHotelIds()).containsExactly("seven_stars");
    }

    private static KnowledgeDocument document(ObjectId id, String text) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id.toHexString());
        document.setText(text);
        return document;
    }
}
