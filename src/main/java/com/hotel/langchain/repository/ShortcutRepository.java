package com.hotel.langchain.repository;

import com.hotel.langchain.model.Shortcut;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ShortcutRepository {

    private final MongoTemplate mongoTemplate;

    public ShortcutRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Само активните бутони (is_active не е false; липсващо поле = активен)
    public List<Shortcut> findAllByHotelId(String hotelId) {
        String collectionName = "shortcuts_" + hotelId;
        System.out.println("looking for collection name: " + collectionName);
        return mongoTemplate.find(new Query(activeCriteria()), Shortcut.class, collectionName);
    }

    public Shortcut findActiveByShortcutId(String hotelId, String shortcutId) {
        Query query = new Query(Criteria.where("shortcutId").is(shortcutId).andOperator(activeCriteria()));
        return mongoTemplate.findOne(query, Shortcut.class, "shortcuts_" + hotelId);
    }

    private Criteria activeCriteria() {
        return Criteria.where("is_active").ne(false);
    }
}