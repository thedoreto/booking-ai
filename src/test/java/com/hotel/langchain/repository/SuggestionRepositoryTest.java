package com.hotel.langchain.repository;

import com.hotel.langchain.model.SuggestionAnalysis;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Анализите за предложения срещу истинска MongoDB (вградена, без Atlas)
class SuggestionRepositoryTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;
    private static SuggestionRepository repository;

    @BeforeAll
    static void startMongo() {
        mongod = Mongod.instance().start(Version.Main.V7_0);
        client = MongoClients.create("mongodb://" + mongod.current().getServerAddress());
        repository = new SuggestionRepository(new MongoTemplate(client, "suggestions_test"));
    }

    @AfterAll
    static void stopMongo() {
        client.close();
        mongod.close();
    }

    @Test
    void latestAnalysisAndItemStatus() {
        String hotel = hotel();
        repository.insert(hotel, analysis(Instant.parse("2026-09-28T08:00:00Z"), item("old", "Старо")));
        SuggestionAnalysis latest = repository.insert(hotel,
                analysis(Instant.parse("2026-09-29T08:00:00Z"), item("i1", "Басейн"), item("i2", "Спа")));

        assertThat(repository.findLatest(hotel)).get().satisfies(a -> {
            assertThat(a.getId()).isEqualTo(latest.getId());
            assertThat(a.getItems()).extracting(SuggestionAnalysis.Item::getTopic).containsExactly("Басейн", "Спа");
        });

        assertThat(repository.updateItemStatus(hotel, latest.getId(), "i2", SuggestionAnalysis.DISMISSED)).isTrue();
        assertThat(repository.updateItemStatus(hotel, latest.getId(), "unknown", SuggestionAnalysis.DISMISSED)).isFalse();
        assertThat(repository.updateItemStatus(hotel, "not-an-id", "i2", SuggestionAnalysis.DISMISSED)).isFalse();
        assertThat(repository.findLatest(hotel).get().getItems())
                .extracting(SuggestionAnalysis.Item::getStatus).containsExactly(SuggestionAnalysis.NEW, SuggestionAnalysis.DISMISSED);

        List<SuggestionAnalysis.Item> handled = repository.findHandled(hotel, 10);
        assertThat(handled).extracting(SuggestionAnalysis.Item::getTopic).containsExactly("Спа");
        assertThat(handled.get(0).getStatus()).isEqualTo(SuggestionAnalysis.DISMISSED);
    }

    @Test
    void emptyHotelHasNoAnalysis() {
        assertThat(repository.findLatest(hotel())).isEmpty();
        assertThat(repository.findHandled(hotel(), 10)).isEmpty();
    }

    private static SuggestionAnalysis analysis(Instant createdAt, SuggestionAnalysis.Item... items) {
        SuggestionAnalysis analysis = new SuggestionAnalysis();
        analysis.setCreatedAt(createdAt);
        analysis.setDays(30);
        analysis.setItems(List.of(items));
        return analysis;
    }

    private static SuggestionAnalysis.Item item(String id, String topic) {
        SuggestionAnalysis.Item item = new SuggestionAnalysis.Item();
        item.setId(id);
        item.setType(SuggestionAnalysis.MISSING_KNOWLEDGE);
        item.setStatus(SuggestionAnalysis.NEW);
        item.setTopic(topic);
        item.setDraft("Чернова с [час].");
        return item;
    }

    private static String hotel() {
        return "h" + UUID.randomUUID().toString().replace("-", "");
    }
}
