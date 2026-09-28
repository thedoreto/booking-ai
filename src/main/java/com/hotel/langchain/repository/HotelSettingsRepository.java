package com.hotel.langchain.repository;

import com.hotel.langchain.model.HotelSettings;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

// Настройките на хотелите – една обща колекция hotel_settings, по един документ на хотел (поле hotelId)
@Repository
public class HotelSettingsRepository {

    private final MongoTemplate mongoTemplate;

    public HotelSettingsRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<HotelSettings> findAll() {
        return mongoTemplate.findAll(HotelSettings.class);
    }
}
