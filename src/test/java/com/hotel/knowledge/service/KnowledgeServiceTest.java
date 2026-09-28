package com.hotel.knowledge.service;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.repository.KnowledgeRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.bson.types.ObjectId;
import com.hotel.knowledge.service.KnowledgeService.EmbeddingFailedException;
import com.hotel.knowledge.service.KnowledgeService.InvalidKnowledgeException;
import com.hotel.knowledge.service.KnowledgeService.KnowledgeChanges;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @Test
    void editWithoutNewTextDoesNotCallGemini() {
        ObjectId id = existing("Паркингът е безплатен.");

        service.update("seven_stars", id.toHexString(),
                new KnowledgeChanges(" Паркинг ", " ", List.of("паркинг", " ", "паркинг", "кола"), null, "Паркингът е безплатен. "));

        ArgumentCaptor<KnowledgeDocument> changes = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).update(eq("seven_stars"), eq(id), changes.capture(), isNull());
        verify(embeddingModel, never()).embed(anyString());
        assertThat(changes.getValue().getTitle()).isEqualTo("Паркинг");
        assertThat(changes.getValue().getCategory()).isNull();
        assertThat(changes.getValue().getTags()).containsExactly("паркинг", "кола");
        assertThat(changes.getValue().getText()).isEqualTo("Паркингът е безплатен.");
    }

    @Test
    void newTextGetsANewEmbedding() {
        ObjectId id = existing("Паркингът е безплатен.");
        when(embeddingModel.embed("Паркингът струва 10 лв.")).thenReturn(Response.from(Embedding.from(new float[]{0.5f, -1f})));

        service.update("seven_stars", id.toHexString(), new KnowledgeChanges(null, null, null, null, "Паркингът струва 10 лв."));

        verify(repository).update(eq("seven_stars"), eq(id), any(), eq(List.of(0.5, -1.0)));
    }

    @Test
    void nothingIsSavedWhenGeminiFails() {
        ObjectId id = existing("Паркингът е безплатен.");
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("503"));

        assertThatThrownBy(() -> service.update("seven_stars", id.toHexString(), new KnowledgeChanges(null, null, null, null, "Нов текст")))
                .isInstanceOf(EmbeddingFailedException.class);
        verify(repository, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void unknownOrInvalidIdIsNotFound() {
        when(repository.findByIds(anyString(), anyList())).thenReturn(List.of());

        assertThat(service.update("seven_stars", new ObjectId().toHexString(), new KnowledgeChanges(null, null, null, null, "Текст"))).isEmpty();
        assertThat(service.update("seven_stars", "not-an-id", new KnowledgeChanges(null, null, null, null, "Текст"))).isEmpty();
        verify(repository, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void textIsRequiredAndLimited() {
        String id = new ObjectId().toHexString();

        assertThatThrownBy(() -> service.update("seven_stars", id, new KnowledgeChanges("Заглавие", null, null, null, "  ")))
                .isInstanceOfSatisfying(InvalidKnowledgeException.class, e -> assertThat(e.getCode()).isEqualTo("TEXT_REQUIRED"));
        String tooLong = "а".repeat(KnowledgeService.MAX_TEXT_LENGTH + 1);
        assertThatThrownBy(() -> service.update("seven_stars", id, new KnowledgeChanges(null, null, null, null, tooLong)))
                .isInstanceOfSatisfying(InvalidKnowledgeException.class, e -> assertThat(e.getCode()).isEqualTo("TEXT_TOO_LONG"));
        verify(repository, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void newKnowledgeAlwaysGetsAnEmbedding() {
        when(embeddingModel.embed("Закуска от 7 до 10.")).thenReturn(Response.from(Embedding.from(new float[]{1f})));
        when(repository.insert(eq("seven_stars"), any())).thenAnswer(invocation -> invocation.getArgument(1));

        KnowledgeDocument created = service.create("seven_stars", new KnowledgeChanges("Закуска", null, null, null, " Закуска от 7 до 10. "));

        assertThat(created.getText()).isEqualTo("Закуска от 7 до 10.");
        assertThat(created.getTitle()).isEqualTo("Закуска");
        assertThat(created.getEmbedding()).containsExactly(1.0);
    }

    @Test
    void newKnowledgeIsNotSavedWhenGeminiFails() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("503"));

        assertThatThrownBy(() -> service.create("seven_stars", new KnowledgeChanges(null, null, null, null, "Текст")))
                .isInstanceOf(EmbeddingFailedException.class);
        assertThatThrownBy(() -> service.create("seven_stars", new KnowledgeChanges(null, null, null, null, " ")))
                .isInstanceOf(InvalidKnowledgeException.class);
        verify(repository, never()).insert(anyString(), any());
    }

    @Test
    void deleteWithInvalidIdDoesNotAskMongo() {
        ObjectId id = new ObjectId();
        when(repository.delete("seven_stars", id)).thenReturn(true);

        assertThat(service.delete("seven_stars", id.toHexString())).isTrue();
        assertThat(service.delete("seven_stars", "not-an-id")).isFalse();
        assertThat(service.delete("seven_stars", null)).isFalse();
        verify(repository).delete(anyString(), any());
    }

    private ObjectId existing(String text) {
        ObjectId id = new ObjectId();
        when(repository.findByIds("seven_stars", List.of(id))).thenReturn(List.of(document(text)));
        when(repository.update(eq("seven_stars"), eq(id), any(), any())).thenReturn(Optional.of(document(text)));
        return id;
    }

    private static KnowledgeDocument document(String text) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setText(text);
        return document;
    }
}
