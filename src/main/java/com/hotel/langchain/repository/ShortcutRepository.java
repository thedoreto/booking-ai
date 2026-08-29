package com.hotel.langchain.repository;

import com.hotel.langchain.model.Shortcut;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ShortcutRepository {

    private final MongoTemplate mongoTemplate;

    public ShortcutRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<Shortcut> findAllByHotelId(String hotelId) {
        String collectionName = "shortcuts_" + hotelId;
        System.out.println("looking for collection name: " + collectionName);
        return mongoTemplate.findAll(Shortcut.class, collectionName);
    }
}