package com.hotel.langchain.repository;

import com.hotel.langchain.model.Shortcut;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortcutRepositoryTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final ShortcutRepository repository = new ShortcutRepository(mongoTemplate);

    @Test
    void allActiveButtonsOfTheHotel() {
        repository.findAllByHotelId("seven_stars");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(Shortcut.class), eq("shortcuts_seven_stars"));
        // Липсващо isActive = активен бутон
        assertThat(query.getValue().getQueryObject()).containsEntry("isActive", new Document("$ne", false));
    }

    @Test
    void buttonsAreSortedByOrderAndThoseWithoutOrderGoLast() {
        when(mongoTemplate.find(any(Query.class), eq(Shortcut.class), eq("shortcuts_seven_stars")))
                .thenReturn(List.of(shortcut("wifi", null), shortcut("parking", 2), shortcut("breakfast", null),
                        shortcut("booking", 1)));

        assertThat(repository.findAllByHotelId("seven_stars")).extracting(Shortcut::getShortcutId)
                .containsExactly("booking", "parking", "breakfast", "wifi");
    }

    @Test
    void oneActiveButtonByShortcutId() {
        repository.findActiveByShortcutId("40_robbers", "parking");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findOne(query.capture(), eq(Shortcut.class), eq("shortcuts_40_robbers"));
        assertThat(query.getValue().getQueryObject().toJson())
                .contains("\"shortcutId\": \"parking\"")
                .contains("\"isActive\": {\"$ne\": false}");
    }

    @Test
    void knowledgeButtonsIncludeInactiveOnes() {
        repository.findKnowledgeShortcuts("40_robbers");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(Shortcut.class), eq("shortcuts_40_robbers"));
        assertThat(query.getValue().getQueryObject()).containsEntry("action.type", "knowledge").doesNotContainKey("isActive");
    }

    @Test
    void adminListIncludesInactiveButtonsInDisplayOrder() {
        when(mongoTemplate.findAll(Shortcut.class, "shortcuts_40_robbers"))
                .thenReturn(List.of(shortcut("wifi", null), shortcut("parking", 1)));

        assertThat(repository.findAll("40_robbers")).extracting(Shortcut::getShortcutId).containsExactly("parking", "wifi");
    }

    @Test
    void updateChangesTheButtonButNotItsIdOrOrder() {
        Shortcut changes = shortcut(null, null);
        changes.setLabel(java.util.Map.of("bg", "Паркинг"));
        changes.setIsActive(false);

        repository.update("40_robbers", "parking", changes);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class),
                eq(Shortcut.class), eq("shortcuts_40_robbers"));
        assertThat(query.getValue().getQueryObject()).containsEntry("shortcutId", "parking");
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        Document unset = (Document) update.getValue().getUpdateObject().get("$unset");
        assertThat(set).containsEntry("isActive", false).containsKey("label").doesNotContainKeys("shortcutId", "order");
        // Без category, guest и action – полетата се махат
        assertThat(unset).containsKeys("category", "guest", "action");
    }

    @Test
    void newOrderIsOneBulkWrite() {
        BulkOperations bulk = mock(BulkOperations.class);
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Shortcut.class, "shortcuts_40_robbers")).thenReturn(bulk);

        repository.updateOrder("40_robbers", List.of("wifi", "parking"));

        verify(bulk, times(2)).updateOne(any(Query.class), any(Update.class));
        verify(bulk).execute();
    }

    @Test
    void emptyOrderWritesNothing() {
        repository.updateOrder("40_robbers", List.of());

        verifyNoInteractions(mongoTemplate);
    }

    private static Shortcut shortcut(String shortcutId, Integer order) {
        Shortcut shortcut = new Shortcut();
        shortcut.setShortcutId(shortcutId);
        shortcut.setOrder(order);
        return shortcut;
    }
}
