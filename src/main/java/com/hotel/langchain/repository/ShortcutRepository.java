package com.hotel.langchain.repository;

import com.hotel.langchain.model.Shortcut;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ShortcutRepository {

    // Бутоните на всеки хотел са в колекция shortcuts_<hotelId>
    private static final String COLLECTION_PREFIX = "shortcuts_";

    private final MongoTemplate mongoTemplate;

    public ShortcutRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Само активните бутони (isActive не е false; липсващо поле = активен)
    public List<Shortcut> findAllByHotelId(String hotelId) {
        return mongoTemplate.find(new Query(activeCriteria()), Shortcut.class, collection(hotelId));
    }

    public Shortcut findActiveByShortcutId(String hotelId, String shortcutId) {
        Query query = new Query(Criteria.where("shortcutId").is(shortcutId).andOperator(activeCriteria()));
        return mongoTemplate.findOne(query, Shortcut.class, collection(hotelId));
    }

    // Всички бутони със знания (action.type knowledge), и неактивните – за админ панела: кои знания се ползват
    public List<Shortcut> findKnowledgeShortcuts(String hotelId) {
        return mongoTemplate.find(new Query(Criteria.where("action.type").is(Shortcut.Action.KNOWLEDGE)), Shortcut.class,
                collection(hotelId));
    }

    private static String collection(String hotelId) {
        return COLLECTION_PREFIX + hotelId;
    }

    private Criteria activeCriteria() {
        return Criteria.where("isActive").ne(false);
    }
}