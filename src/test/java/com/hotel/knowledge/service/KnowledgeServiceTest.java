package com.hotel.knowledge.service;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.repository.KnowledgeRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final KnowledgeService service = new KnowledgeService(repository, embeddingModel);

    @Test
    void buttonGetsTheTextsInTheOrderOfTheRepositoryWithoutEmptyOnes() {
        ObjectId parking = new ObjectId();
        ObjectId empty = new ObjectId();
        ObjectId breakfast = new ObjectId();
        when(repository.findByIds("seven_stars", List.of(parking, empty, breakfast)))
                .thenReturn(List.of(document("Паркингът е безплатен."), document(" "), document(null), document("Закуска от 7 до 10.")));

        assertThat(service.textsByIds("seven_stars", Arrays.asList(parking, null, empty, breakfast)))
                .containsExactly("Паркингът е безплатен.", "Закуска от 7 до 10.");
    }

    @Test
    void buttonWithoutIdsDoesNotAskMongo() {
        assertThat(service.textsByIds("seven_stars", null)).isEmpty();
        assertThat(service.textsByIds("seven_stars", List.of())).isEmpty();
        assertThat(service.textsByIds("seven_stars", Arrays.asList((ObjectId) null))).isEmpty();
        verify(repository, never()).findByIds(anyString(), anyList());
    }

    @Test
    void questionIsEmbeddedAndSearchedInTheKnowledgeOfTheHotel() {
        when(embeddingModel.embed("Има ли паркинг?")).thenReturn(Response.from(Embedding.from(new float[]{0.5f, -1f})));
        List<KnowledgeDocument> found = List.of(document("Паркингът е безплатен."));
        when(repository.searchByVector("seven_stars", List.of(0.5, -1.0))).thenReturn(found);

        assertThat(service.findRelevant("seven_stars", "Има ли паркинг?")).isSameAs(found);
    }

    private static KnowledgeDocument document(String text) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setText(text);
        return document;
    }
}
