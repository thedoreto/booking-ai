package com.hotel.langchain.repository;

import com.hotel.langchain.model.Shortcut;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ShortcutRepository {

    // Бутоните на всеки хотел са в колекция shortcuts_<hotelId>
    private static final String COLLECTION_PREFIX = "shortcuts_";

    private final MongoTemplate mongoTemplate;

    public ShortcutRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // Само активните бутони (isActive не е false; липсващо поле = активен), в реда за чата (Shortcut.DISPLAY_ORDER).
    // Подрежда се тук, не в Mongo – Mongo слага бутоните без order най-отпред
    public List<Shortcut> findAllByHotelId(String hotelId) {
        return mongoTemplate.find(new Query(activeCriteria()), Shortcut.class, collection(hotelId)).stream()
                .sorted(Shortcut.DISPLAY_ORDER)
                .toList();
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

    // Всички бутони на хотела, и неактивните – за админ панела, в реда за чата
    public List<Shortcut> findAll(String hotelId) {
        return mongoTemplate.findAll(Shortcut.class, collection(hotelId)).stream()
                .sorted(Shortcut.DISPLAY_ORDER)
                .toList();
    }

    // Бутонът по shortcutId, и неактивен
    public Optional<Shortcut> findByShortcutId(String hotelId, String shortcutId) {
        return Optional.ofNullable(mongoTemplate.findOne(new Query(Criteria.where("shortcutId").is(shortcutId)),
                Shortcut.class, collection(hotelId)));
    }

    public Shortcut insert(String hotelId, Shortcut shortcut) {
        return mongoTemplate.insert(shortcut, collection(hotelId));
    }

    // Сменя label, category, isActive, guest и action (null – полето се маха); shortcutId и order не се пипат.
    // Връща новия бутон; празно – няма такъв
    public Optional<Shortcut> update(String hotelId, String shortcutId, Shortcut changes) {
        Update update = new Update();
        setOrUnset(update, "label", changes.getLabel());
        setOrUnset(update, "category", changes.getCategory());
        update.set("isActive", changes.isActive());
        setOrUnset(update, "guest", changes.getGuest());
        setOrUnset(update, "action", changes.getAction());
        return Optional.ofNullable(mongoTemplate.findAndModify(new Query(Criteria.where("shortcutId").is(shortcutId)),
                update, FindAndModifyOptions.options().returnNew(true), Shortcut.class, collection(hotelId)));
    }

    // true – бутонът е изтрит; false – няма такъв
    public boolean delete(String hotelId, String shortcutId) {
        return mongoTemplate.remove(new Query(Criteria.where("shortcutId").is(shortcutId)), collection(hotelId))
                .getDeletedCount() > 0;
    }

    // Редът в чата: order = 1, 2, ... в реда на shortcutIds (едно bulk записване)
    public void updateOrder(String hotelId, List<String> shortcutIds) {
        if (shortcutIds.isEmpty()) {
            return;
        }
        BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Shortcut.class, collection(hotelId));
        for (int i = 0; i < shortcutIds.size(); i++) {
            bulk.updateOne(new Query(Criteria.where("shortcutId").is(shortcutIds.get(i))), new Update().set("order", i + 1));
        }
        bulk.execute();
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    private static String collection(String hotelId) {
        return COLLECTION_PREFIX + hotelId;
    }

    private Criteria activeCriteria() {
        return Criteria.where("isActive").ne(false);
    }
}