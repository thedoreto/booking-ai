package com.hotel.knowledge.repository;

import com.hotel.knowledge.model.KnowledgeDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.mongodb.client.result.DeleteResult;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void allDocumentsOfTheHotelSortedWithoutEmbedding() {
        repository.findAll("40_robbers");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(KnowledgeDocument.class), eq("knowledge_40_robbers"));
        assertThat(query.getValue().getFieldsObject()).containsEntry("embedding", 0);
        assertThat(query.getValue().getSortObject().toJson()).isEqualTo("{\"category\": 1, \"title\": 1}");
    }

    @Test
    void updateSetsTheFieldsUnsetsTheEmptyOnesAndKeepsTheRest() {
        ObjectId id = new ObjectId();
        KnowledgeDocument changes = new KnowledgeDocument();
        changes.setTitle("Паркинг");
        changes.setTags(List.of());
        changes.setText("Нов текст");

        repository.update("40_robbers", id, changes, List.of(0.5, -1.0));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update =
                ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(query.capture(), update.capture(),
                any(FindAndModifyOptions.class), eq(KnowledgeDocument.class),
                eq("knowledge_40_robbers"));
        assertThat(query.getValue().getQueryObject()).containsEntry("_id", id);
        assertThat(query.getValue().getFieldsObject()).containsEntry("embedding", 0);
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        Document unset = (Document) update.getValue().getUpdateObject().get("$unset");
        assertThat(set).containsEntry("title", "Паркинг").containsEntry("text", "Нов текст")
                .containsEntry("embedding", List.of(0.5, -1.0));
        assertThat(unset).containsKeys("category", "tags", "source");
        // metadata и други полета не се пипат
        assertThat(set).doesNotContainKey("metadata");
        assertThat(unset).doesNotContainKey("metadata");
    }

    @Test
    void updateWithoutNewEmbeddingKeepsTheOldOne() {
        KnowledgeDocument changes = new KnowledgeDocument();
        changes.setText("Текст");

        repository.update("40_robbers", new ObjectId(), changes, null);

        ArgumentCaptor<Update> update =
                ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), update.capture(),
                any(FindAndModifyOptions.class), eq(KnowledgeDocument.class), anyString());
        assertThat(update.getValue().getUpdateObject().toJson()).doesNotContain("embedding");
    }

    @Test
    void insertAndDeleteGoToTheHotelsCollection() {
        KnowledgeDocument doc = document(new ObjectId(), "Текст");
        ObjectId id = new ObjectId();
        when(mongoTemplate.remove(any(Query.class), eq("knowledge_40_robbers")))
                .thenReturn(DeleteResult.acknowledged(1), DeleteResult.acknowledged(0));

        repository.insert("40_robbers", doc);

        verify(mongoTemplate).insert(doc, "knowledge_40_robbers");
        assertThat(repository.delete("40_robbers", id)).isTrue();
        assertThat(repository.delete("40_robbers", id)).isFalse();
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, times(2)).remove(query.capture(), eq("knowledge_40_robbers"));
        assertThat(query.getValue().getQueryObject()).containsEntry("_id", id);
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
