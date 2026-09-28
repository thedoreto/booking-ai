package com.hotel.langchain.repository;

import com.hotel.langchain.model.Shortcut;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    void oneActiveButtonByShortcutId() {
        repository.findActiveByShortcutId("40_robbers", "parking");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findOne(query.capture(), eq(Shortcut.class), eq("shortcuts_40_robbers"));
        assertThat(query.getValue().getQueryObject().toJson())
                .contains("\"shortcutId\": \"parking\"")
                .contains("\"isActive\": {\"$ne\": false}");
    }
}
