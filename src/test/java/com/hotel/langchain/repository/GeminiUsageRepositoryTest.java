package com.hotel.langchain.repository;

import com.hotel.langchain.model.GeminiUsage;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Броячите на Gemini срещу истинска MongoDB (вградена, без Atlas): upsert с $inc и индексите.
class GeminiUsageRepositoryTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;
    private static MongoTemplate mongoTemplate;
    private static GeminiUsageRepository repository;

    @BeforeAll
    static void startMongo() {
        mongod = Mongod.instance().start(Version.Main.V7_0);
        client = MongoClients.create("mongodb://" + mongod.current().getServerAddress());
        mongoTemplate = new MongoTemplate(client, "gemini_usage_test");
        repository = new GeminiUsageRepository(mongoTemplate);
    }

    @AfterAll
    static void stopMongo() {
        client.close();
        mongod.close();
    }

    @Test
    void callsOfTheSameDaySourceAndModelAreAddedToOneDocument() {
        String hotel = hotel();
        repository.add(hotel, onDay("2026-09-29", GeminiUsage.tokens(GeminiUsage.CHAT, "gemini-test", 2, 0, 300, 40)));
        repository.add(hotel, onDay("2026-09-29", GeminiUsage.tokens(GeminiUsage.CHAT, "gemini-test", 1, 1, 100, 0)));

        List<GeminiUsage> usage = repository.findFrom(hotel, "2026-09-01");

        assertThat(usage).hasSize(1);
        GeminiUsage chat = usage.get(0);
        assertThat(chat.getDay()).isEqualTo("2026-09-29");
        assertThat(chat.getCalls()).isEqualTo(3);
        assertThat(chat.getErrors()).isEqualTo(1);
        assertThat(chat.getInputTokens()).isEqualTo(400);
        assertThat(chat.getOutputTokens()).isEqualTo(40);
        assertThat(chat.getCharacters()).isNull();
        assertThat(chat.getUpdatedAt()).isNotNull();
    }

    @Test
    void otherDaySourceOrModelIsAnotherDocument() {
        String hotel = hotel();
        repository.add(hotel, onDay("2026-09-28", GeminiUsage.tokens(GeminiUsage.CHAT, "gemini-test", 1, 0, 10, 1)));
        repository.add(hotel, onDay("2026-09-29", GeminiUsage.tokens(GeminiUsage.CHAT, "gemini-test", 1, 0, 10, 1)));
        repository.add(hotel, onDay("2026-09-29", GeminiUsage.characters(GeminiUsage.CHAT_EMBEDDING, "gemini-embedding-001", 1, 0, 15)));
        repository.add(hotel, onDay("2026-09-29", GeminiUsage.characters(GeminiUsage.CHAT_EMBEDDING, "gemini-embedding-001", 1, 0, 20)));

        List<GeminiUsage> usage = repository.findFrom(hotel, "2026-09-29");

        // 28.09 е преди периода; embedding-ът е отделен документ без токени
        assertThat(usage).extracting(GeminiUsage::getSource)
                .containsExactly(GeminiUsage.CHAT, GeminiUsage.CHAT_EMBEDDING);
        GeminiUsage embedding = usage.get(1);
        assertThat(embedding.getCalls()).isEqualTo(2);
        assertThat(embedding.getCharacters()).isEqualTo(35);
        assertThat(embedding.getInputTokens()).isNull();
    }

    @Test
    void collectionHasTtlAndUniqueDayIndexes() {
        String hotel = hotel();
        repository.add(hotel, onDay("2026-09-29", GeminiUsage.tokens(GeminiUsage.CHAT, "gemini-test", 1, 0, 1, 1)));

        List<Document> indexes = mongoTemplate.getCollection("gemini_usage_" + hotel).listIndexes().into(new ArrayList<>());

        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.getString("name")).isEqualTo("updatedAt_ttl");
            assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isEqualTo(180L * 24 * 60 * 60);
        });
        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.getString("name")).isEqualTo("day_source_model");
            assertThat(index.getBoolean("unique")).isTrue();
        });
    }

    private static GeminiUsage onDay(String day, GeminiUsage usage) {
        usage.setDay(day);
        return usage;
    }

    // Всеки тест – в своя колекция
    private static String hotel() {
        return "hotel_" + UUID.randomUUID().toString().replace("-", "");
    }
}
