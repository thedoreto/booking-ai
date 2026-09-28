package com.hotel.langchain.repository;

import com.hotel.langchain.model.HotelSettings;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    // Направо от Mongo, без кеш – за входа в админ панела (нова парола важи веднага)
    public Optional<HotelSettings> findByHotelId(String hotelId) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("hotelId").is(hotelId)), HotelSettings.class));
    }
}
