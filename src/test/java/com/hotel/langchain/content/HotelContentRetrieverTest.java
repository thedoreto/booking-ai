package com.hotel.langchain.content;

import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.langchain.context.TenantContext;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HotelContentRetrieverTest {

    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final HotelContentRetriever retriever = new HotelContentRetriever(knowledgeService);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void searchesTheKnowledgeOfTheHotelOfTheRequest() {
        TenantContext.setHotelId("seven_stars");
        KnowledgeDocument parking = new KnowledgeDocument();
        parking.setText("Паркингът е безплатен.");
        when(knowledgeService.findRelevant("seven_stars", "Има ли паркинг?")).thenReturn(List.of(parking));

        List<Content> contents = retriever.retrieve(Query.from("Има ли паркинг?"));

        assertThat(contents).extracting(content -> content.textSegment().text()).containsExactly("Паркингът е безплатен.");
    }

    @Test
    void scoresOfTheFoundKnowledgeAreKeptForTheLogRounded() {
        TenantContext.setHotelId("seven_stars");
        KnowledgeDocument parking = document("Паркингът е безплатен.", 0.83456);
        KnowledgeDocument breakfast = document("Закуска от 7 до 10.", 0.6);
        when(knowledgeService.findRelevant("seven_stars", "Има ли паркинг?")).thenReturn(List.of(parking, breakfast));

        retriever.retrieve(Query.from("Има ли паркинг?"));

        assertThat(TenantContext.getKnowledgeScores()).containsExactly(0.835, 0.6);
    }

    private static KnowledgeDocument document(String text, Double score) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setText(text);
        document.setScore(score);
        return document;
    }

    @Test
    void nothingFoundOrErrorGivesNoContent() {
        TenantContext.setHotelId("seven_stars");
        when(knowledgeService.findRelevant("seven_stars", "Нищо")).thenReturn(List.of());
        when(knowledgeService.findRelevant("seven_stars", "Грешка")).thenThrow(new RuntimeException("Atlas down"));

        assertThat(retriever.retrieve(Query.from("Нищо"))).isEmpty();
        assertThat(retriever.retrieve(Query.from("Грешка"))).isEmpty();
    }

    @Test
    void withoutHotelNothingIsSearched() {
        assertThat(retriever.retrieve(Query.from("Има ли паркинг?"))).isEmpty();
        verifyNoInteractions(knowledgeService);
    }
}
