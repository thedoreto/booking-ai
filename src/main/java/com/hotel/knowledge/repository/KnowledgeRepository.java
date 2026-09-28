package com.hotel.knowledge.repository;

import com.hotel.knowledge.model.KnowledgeDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class KnowledgeRepository {

    // Знанията на всеки хотел са в колекция knowledge_<hotelId>
    private static final String COLLECTION_PREFIX = "knowledge_";

    private final MongoTemplate mongoTemplate;

    public KnowledgeRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Хотелите, които имат знания (колекция knowledge_<hotelId>)
    public Set<String> findHotelIds() {
        return mongoTemplate.getCollectionNames().stream()
                .filter(name -> name.startsWith(COLLECTION_PREFIX))
                .map(name -> name.substring(COLLECTION_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    // Документите по _id, в реда на ids – директно от колекцията, без vector search. Липсващ документ се пропуска.
    // Без embedding – не е нужен, а е голям.
    public List<KnowledgeDocument> findByIds(String hotelId, List<ObjectId> ids) {
        Query query = new Query(Criteria.where("_id").in(ids));
        query.fields().exclude("embedding");
        Map<String, KnowledgeDocument> byId = mongoTemplate.find(query, KnowledgeDocument.class, collection(hotelId))
                .stream()
                .collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));
        return ids.stream()
                .map(id -> byId.get(id.toHexString()))
                .filter(Objects::nonNull)
                .toList();
    }

    // Всички знания на хотела за админ панела, по категория и заглавие. Без embedding.
    public List<KnowledgeDocument> findAll(String hotelId) {
        Query query = new Query().with(Sort.by("category", "title"));
        query.fields().exclude("embedding");
        return mongoTemplate.find(query, KnowledgeDocument.class, collection(hotelId));
    }

    // Нов документ (с embedding); връща го със _id
    public KnowledgeDocument insert(String hotelId, KnowledgeDocument document) {
        return mongoTemplate.insert(document, collection(hotelId));
    }

    // true – документът е изтрит; false – няма такъв
    public boolean delete(String hotelId, ObjectId id) {
        return mongoTemplate.remove(new Query(Criteria.where("_id").is(id)), collection(hotelId)).getDeletedCount() > 0;
    }

    // Записва преводите (код на език → текст); другите езици остават. Връща новия документ без embedding.
    public Optional<KnowledgeDocument> setTranslations(String hotelId, ObjectId id, Map<String, String> translations) {
        Update update = new Update();
        translations.forEach((language, text) -> update.set("translations." + language, text));
        Query query = new Query(Criteria.where("_id").is(id));
        query.fields().exclude("embedding");
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
                KnowledgeDocument.class, collection(hotelId)));
    }

    // Сменя title, category, tags, source и text на документа (празно/null – полето се маха); embedding – само ако не е null.
    // Останалото в документа (metadata…) не се пипа. Връща новия документ без embedding; празно – няма такъв документ.
    public Optional<KnowledgeDocument> update(String hotelId, ObjectId id, KnowledgeDocument changes, List<Double> embedding) {
        Update update = new Update();
        setOrUnset(update, "title", changes.getTitle());
        setOrUnset(update, "category", changes.getCategory());
        setOrUnset(update, "tags", changes.getTags() == null || changes.getTags().isEmpty() ? null : changes.getTags());
        setOrUnset(update, "source", changes.getSource());
        update.set("text", changes.getText());
        if (embedding != null) {
            update.set("embedding", embedding);
        }
        Query query = new Query(Criteria.where("_id").is(id));
        query.fields().exclude("embedding");
        return Optional.ofNullable(mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
                KnowledgeDocument.class, collection(hotelId)));
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    public List<KnowledgeDocument> searchByVector(String hotelId, List<Double> embedding) {
        Document vectorSearch = new Document("$vectorSearch",
                new Document("index", "autoembed_index")
                        .append("path", "embedding")
                        .append("queryVector", embedding)
                        .append("numCandidates", 100)
                        .append("limit", 5)
        );

        Aggregation aggregation = Aggregation.newAggregation(context -> vectorSearch);

        return mongoTemplate.aggregate(aggregation, collection(hotelId), KnowledgeDocument.class).getMappedResults();
    }

    private static String collection(String hotelId) {
        return COLLECTION_PREFIX + hotelId;
    }
}
