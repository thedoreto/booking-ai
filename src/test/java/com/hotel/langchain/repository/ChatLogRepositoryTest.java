package com.hotel.langchain.repository;

import com.hotel.langchain.model.ChatLog;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatLogRepositoryTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final IndexOperations indexOps = mock(IndexOperations.class);
    private final ChatLogRepository repository = new ChatLogRepository(mongoTemplate);

    @Test
    void insertsIntoHotelCollectionAndCreatesIndexesOnce() {
        when(mongoTemplate.indexOps(anyString())).thenReturn(indexOps);
        ChatLog log = new ChatLog();

        repository.insert("seven_stars", log);
        repository.insert("seven_stars", log);

        verify(mongoTemplate, times(2)).insert(log, "logs_seven_stars");
        // TTL и flowId – само при първия запис
        verify(mongoTemplate, times(2)).indexOps("logs_seven_stars");
    }

    @Test
    void upsertsFlowStepByFlowId() {
        when(mongoTemplate.indexOps(anyString())).thenReturn(indexOps);
        ChatLog flow = new ChatLog();
        flow.setFlowId("flow-1");
        flow.setType("new_booking");
        flow.setStartedBy("button");
        flow.setStatus("rooms_shown");
        flow.setUserId("user-1");
        ChatLog.Step step = new ChatLog.Step();
        step.setStep("search");
        step.setAt(Instant.parse("2026-09-28T08:00:00Z"));

        repository.addFlowStep("seven_stars", flow, step);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).upsert(query.capture(), update.capture(), eq(ChatLog.class), eq("logs_seven_stars"));

        assertThat(query.getValue().getQueryObject()).containsEntry("flowId", "flow-1");
        Document changes = update.getValue().getUpdateObject();
        // Първата стъпка задава вида и началото; всяка стъпка сменя статуса и се добавя към steps
        assertThat(changes.get("$setOnInsert", Document.class))
                .containsEntry("type", "new_booking")
                .containsEntry("startedBy", "button")
                .containsEntry("userId", "user-1")
                .containsEntry("hotelId", "seven_stars")
                .containsEntry("timestamp", step.getAt());
        assertThat(changes.get("$set", Document.class))
                .containsEntry("status", "rooms_shown")
                .containsEntry("updatedAt", step.getAt());
        assertThat(changes.get("$push", Document.class).get("steps")).isSameAs(step);
        assertThat(changes).doesNotContainKey("$inc");
    }

    @Test
    void addsGeminiUsageOfTheStepToTheFlow() {
        when(mongoTemplate.indexOps(anyString())).thenReturn(indexOps);
        ChatLog flow = new ChatLog();
        flow.setFlowId("flow-1");
        ChatLog.Gemini gemini = new ChatLog.Gemini();
        gemini.setCalls(1);
        gemini.setInputTokens(100);
        gemini.setOutputTokens(20);
        ChatLog.Step step = new ChatLog.Step();
        step.setGemini(gemini);

        repository.addFlowStep("seven_stars", flow, step);

        ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongoTemplate).upsert(any(Query.class), update.capture(),
                eq(ChatLog.class), eq("logs_seven_stars"));
        assertThat(update.getValue().getUpdateObject().get("$inc", Document.class))
                .containsEntry("gemini.calls", 1)
                .containsEntry("gemini.inputTokens", 100)
                .containsEntry("gemini.outputTokens", 20);
    }
}
