package com.hotel.langchain.repository;

import com.hotel.langchain.model.Translation;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

// Преводите – обща за всички хотели колекция translations
@Repository
public class TranslationRepository {

    private final MongoTemplate mongoTemplate;

    public TranslationRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<Translation> findAll() {
        return mongoTemplate.findAll(Translation.class);
    }
}
