package com.hotel.langchain.repository;

import com.hotel.langchain.model.Translation;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

// Преводите: обща за всички хотели колекция translations и преводи на всеки хотел в translations_<hotelId>
@Repository
public class TranslationRepository {

    private static final String COMMON_COLLECTION = "translations";
    private static final String HOTEL_COLLECTION_PREFIX = "translations_";

    private final MongoTemplate mongoTemplate;

    public TranslationRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<Translation> findCommon() {
        return mongoTemplate.findAll(Translation.class, COMMON_COLLECTION);
    }

    // Хотел без колекция – празен списък
    public List<Translation> findByHotelId(String hotelId) {
        return mongoTemplate.findAll(Translation.class, HOTEL_COLLECTION_PREFIX + hotelId);
    }
}
