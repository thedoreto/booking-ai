package com.hotel.config;

import com.hotel.langchain.model.ChatLog;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.UpdateMapper;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Записаните документи (logs_<hotelId>) са без _class и без празни полета – и отделният запис, и стъпката в $push
class MongoConfigTest {

    private final MongoCustomConversions conversions = new MongoCustomConversions(List.of());
    private final MongoMappingContext context = new MongoMappingContext();
    private final MappingMongoConverter converter;

    MongoConfigTest() {
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        MongoDatabaseFactory factory = mock(MongoDatabaseFactory.class);
        when(factory.getExceptionTranslator()).thenReturn(new MongoExceptionTranslator());
        converter = new MongoConfig().mappingMongoConverter(factory, context, conversions);
        converter.afterPropertiesSet();
    }

    @Test
    void insertedDocumentHasNoClassAndNoEmptyFields() {
        ChatLog log = new ChatLog();
        log.setType("chat");
        log.setTimestamp(Instant.parse("2026-09-28T08:00:00Z"));
        log.setDetails(Map.of("shortcutId", "parking"));

        Document document = new Document();
        converter.write(log, document);

        assertThat(document).containsOnlyKeys("type", "timestamp", "details");
        assertThat(document.get("details", Document.class)).isEqualTo(new Document("shortcutId", "parking"));
    }

    @Test
    void pushedStepHasNoClass() {
        ChatLog.Step step = new ChatLog.Step();
        step.setStep("search");
        step.setAt(Instant.parse("2026-09-28T08:00:00Z"));

        Document update = new UpdateMapper(converter).getMappedObject(
                new Update().push("steps", step).getUpdateObject(), context.getPersistentEntity(ChatLog.class));

        Document pushed = update.get("$push", Document.class).get("steps", Document.class);
        assertThat(pushed).containsOnlyKeys("step", "at");
    }

    @Test
    void documentWithoutClassIsReadBack() {
        Document document = new Document("type", "chat").append("outcome", "ok")
                .append("steps", List.of(new Document("step", "search")));

        ChatLog log = converter.read(ChatLog.class, document);

        assertThat(log.getType()).isEqualTo("chat");
        assertThat(log.getSteps()).extracting(ChatLog.Step::getStep).containsExactly("search");
    }
}
