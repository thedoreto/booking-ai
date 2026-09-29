package com.hotel.langchain.repository;

import com.hotel.langchain.log.ChatFlow;
import com.hotel.langchain.log.ChatLogEntry;
import com.hotel.langchain.log.ChatReports;
import com.hotel.langchain.model.ChatLog;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Логовете на всеки хотел са в колекция logs_<hotelId>. Записите се трият автоматично след RETENTION
// (TTL индекс по timestamp), индексите се създават при първия запис в колекцията.
@Repository
public class ChatLogRepository {

    private static final String COLLECTION_PREFIX = "logs_";
    private static final Duration RETENTION = Duration.ofDays(180);

    private final MongoTemplate mongoTemplate;
    // Колекции, за които индексите вече са проверени от тази инстанция
    private final Set<String> indexedCollections = ConcurrentHashMap.newKeySet();

    public ChatLogRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void insert(String hotelId, ChatLog log) {
        mongoTemplate.insert(log, collection(hotelId));
    }

    // Стъпка в действие: първата стъпка създава записа (upsert), следващите го допълват.
    // От flow се взимат flowId и статусът; type, startedBy и userId се записват само от първата стъпка.
    public void addFlowStep(String hotelId, ChatLog flow, ChatLog.Step step) {
        Update update = new Update()
                .setOnInsert("timestamp", step.getAt())
                .setOnInsert("hotelId", hotelId)
                .setOnInsert("userId", flow.getUserId())
                .setOnInsert("type", flow.getType())
                .setOnInsert("startedBy", flow.getStartedBy())
                .set("status", flow.getStatus())
                .set("updatedAt", step.getAt())
                .push("steps", step);
        ChatLog.Gemini gemini = step.getGemini();
        if (gemini != null) {
            update.inc("gemini.calls", gemini.getCalls())
                    .inc("gemini.inputTokens", gemini.getInputTokens())
                    .inc("gemini.outputTokens", gemini.getOutputTokens());
        }
        Query query = new Query(Criteria.where("flowId").is(flow.getFlowId()));
        mongoTemplate.upsert(query, update, ChatLog.class, collection(hotelId));
    }

    // ---- Отчетите за админ панела (записите от from насам) ----

    public ChatReports.Summary summary(String hotelId, Instant from) {
        List<Document> pipeline = new ArrayList<>(stepsFrom(from));
        pipeline.add(new Document("$group", new Document("_id", null)
                .append("questions", countIf(isStep(ChatLogEntry.CHAT)))
                .append("buttons", countIf(isStep(ChatLogEntry.SHORTCUT)))
                .append("bookings", countIf(isOk(ChatLogEntry.BOOKING)))
                .append("bookedRooms", sumIf(isOk(ChatLogEntry.BOOKING),
                        new Document("$size", new Document("$ifNull", List.of("$step.details.bookingIds", List.of())))))
                .append("bookedTotal", sumIf(isOk(ChatLogEntry.BOOKING), number("$step.details.totalPrice")))
                .append("cancellations", countIf(isOk(ChatLogEntry.CANCEL)))
                .append("canceledTotal", sumIf(isOk(ChatLogEntry.CANCEL), number("$step.details.totalPrice")))
                .append("users", new Document("$addToSet", "$userId"))
                .append("steps", new Document("$sum", 1))
                .append("guestSteps", countIf(new Document("$eq", List.of(new Document("$ifNull", List.of("$userId", "")), ""))))));
        Document d = aggregate(hotelId, pipeline).stream().findFirst().orElse(new Document());
        // $addToSet пази и null (гостите) – той не е потребител
        long users = d.getList("users", Object.class, List.of()).stream().filter(u -> u != null).count();
        return new ChatReports.Summary(asLong(d, "questions"), asLong(d, "buttons"), asLong(d, "bookings"),
                asLong(d, "bookedRooms"), asDouble(d, "bookedTotal"), asLong(d, "cancellations"),
                asDouble(d, "canceledTotal"), users, asLong(d, "steps"), asLong(d, "guestSteps"));
    }

    public ChatReports.BookingFunnel bookingFunnel(String hotelId, Instant from) {
        Document searched = anyStep(new Document("$eq", List.of("$$s.step", ChatLogEntry.SEARCH)));
        Document roomsShown = anyStep(stepWithOutcome(ChatLogEntry.SEARCH, ChatLogEntry.OK));
        Document noRooms = anyStep(stepWithOutcome(ChatLogEntry.SEARCH, ChatLogEntry.NO_RESULT));
        Document booked = anyStep(stepWithOutcome(ChatLogEntry.BOOKING, ChatLogEntry.OK));
        Document bookingTried = anyStep(new Document("$eq", List.of("$$s.step", ChatLogEntry.BOOKING)));
        List<Document> pipeline = List.of(
                new Document("$match", new Document("type", ChatFlow.NEW_BOOKING)
                        .append("timestamp", new Document("$gte", Date.from(from)))),
                new Document("$project", new Document("startedBy", 1)
                        .append("searched", searched)
                        .append("roomsShown", roomsShown)
                        .append("noRooms", noRooms)
                        .append("booked", booked)
                        .append("bookingTried", bookingTried)),
                new Document("$group", new Document("_id", null)
                        .append("started", new Document("$sum", 1))
                        .append("fromChat", countIf(new Document("$eq", List.of("$startedBy", ChatFlow.STARTED_BY_CHAT))))
                        .append("fromButton", countIf(new Document("$eq", List.of("$startedBy", ChatFlow.STARTED_BY_BUTTON))))
                        .append("searched", countIf("$searched"))
                        .append("roomsShown", countIf("$roomsShown"))
                        .append("booked", countIf("$booked"))
                        .append("onlyNoRooms", countIf(new Document("$and", List.of("$noRooms", new Document("$not", List.of("$roomsShown"))))))
                        .append("bookingFailed", countIf(new Document("$and", List.of("$bookingTried", new Document("$not", List.of("$booked"))))))));
        Document d = aggregate(hotelId, pipeline).stream().findFirst().orElse(new Document());
        return new ChatReports.BookingFunnel(asLong(d, "started"), asLong(d, "fromChat"), asLong(d, "fromButton"),
                asLong(d, "searched"), asLong(d, "roomsShown"), asLong(d, "booked"), asLong(d, "onlyNoRooms"),
                asLong(d, "bookingFailed"));
    }

    // Най-честите търсения без свободни стаи (първо най-честите, после най-скорошните), най-много limit
    public List<ChatReports.NoRoomsSearch> noRoomsSearches(String hotelId, Instant from, int limit) {
        List<Document> pipeline = new ArrayList<>(stepsFrom(from));
        pipeline.add(new Document("$match", new Document("step.step", ChatLogEntry.SEARCH)
                .append("step.outcome", ChatLogEntry.NO_RESULT)));
        pipeline.add(new Document("$group", new Document("_id", new Document("startDate", "$step.details.startDate")
                .append("endDate", "$step.details.endDate")
                .append("roomType", "$step.details.roomType"))
                .append("count", new Document("$sum", 1))
                .append("last", new Document("$max", "$step.at"))));
        pipeline.add(new Document("$sort", new Document("count", -1).append("last", -1)));
        pipeline.add(new Document("$limit", limit));
        return aggregate(hotelId, pipeline).stream().map(d -> {
            Document id = d.get("_id", Document.class);
            return new ChatReports.NoRoomsSearch(id.getString("startDate"), id.getString("endDate"),
                    id.getString("roomType"), asLong(d, "count"));
        }).toList();
    }

    public ChatReports.GeminiShare geminiShare(String hotelId, Instant from) {
        List<Document> pipeline = new ArrayList<>(stepsFrom(from));
        pipeline.add(new Document("$group", new Document("_id", null)
                .append("steps", new Document("$sum", 1))
                .append("withGemini", countIf(new Document("$gt", List.of(number("$step.gemini.calls"), 0))))
                .append("limitMinute", countIf(new Document("$eq", List.of("$step.errorType", ChatLogEntry.HOTEL_LIMIT_MINUTE))))
                .append("limitDay", countIf(new Document("$eq", List.of("$step.errorType", ChatLogEntry.HOTEL_LIMIT_DAY))))));
        Document d = aggregate(hotelId, pipeline).stream().findFirst().orElse(new Document());
        return new ChatReports.GeminiShare(asLong(d, "steps"), asLong(d, "withGemini"), asLong(d, "limitMinute"),
                asLong(d, "limitDay"));
    }

    // Натиснатите бутони – първо най-натисканите
    public List<ChatReports.ButtonUsage> buttonUsage(String hotelId, Instant from) {
        List<Document> pipeline = new ArrayList<>(stepsFrom(from));
        pipeline.add(new Document("$match", new Document("step.step", ChatLogEntry.SHORTCUT)));
        // $last взима надписа от най-новия запис
        pipeline.add(new Document("$sort", new Document("timestamp", 1)));
        pipeline.add(new Document("$group", new Document("_id", "$step.details.shortcutId")
                .append("label", new Document("$last", "$step.details.label"))
                .append("count", new Document("$sum", 1))
                .append("noResult", countIf(new Document("$eq", List.of("$step.outcome", ChatLogEntry.NO_RESULT))))));
        pipeline.add(new Document("$sort", new Document("count", -1).append("_id", 1)));
        return aggregate(hotelId, pipeline).stream()
                .map(d -> new ChatReports.ButtonUsage(d.getString("_id"), d.getString("label"), asLong(d, "count"),
                        asLong(d, "noResult")))
                .toList();
    }

    // Записите от from насам като стъпки: { userId, timestamp, step: { step, outcome, errorType, details, gemini, at } }.
    // Отделният запис става една стъпка (type → step), действието – всичките си стъпки.
    private static List<Document> stepsFrom(Instant from) {
        Document single = new Document("step", "$type").append("outcome", "$outcome").append("errorType", "$errorType")
                .append("details", "$details").append("gemini", "$gemini").append("at", "$timestamp");
        return List.of(
                new Document("$match", new Document("timestamp", new Document("$gte", Date.from(from)))),
                new Document("$project", new Document("userId", 1).append("timestamp", 1)
                        .append("step", new Document("$ifNull", List.of("$steps", List.of(single))))),
                new Document("$unwind", "$step"));
    }

    private List<Document> aggregate(String hotelId, List<Document> pipeline) {
        return mongoTemplate.getCollection(collection(hotelId)).aggregate(pipeline).into(new ArrayList<>());
    }

    private static Document isStep(String step) {
        return new Document("$eq", List.of("$step.step", step));
    }

    private static Document isOk(String step) {
        return new Document("$and", List.of(isStep(step), new Document("$eq", List.of("$step.outcome", ChatLogEntry.OK))));
    }

    // Условие за стъпка $$s в anyStep
    private static Document stepWithOutcome(String step, String outcome) {
        return new Document("$and", List.of(new Document("$eq", List.of("$$s.step", step)),
                new Document("$eq", List.of("$$s.outcome", outcome))));
    }

    // Има ли стъпка в записа, за която условието (с $$s) е вярно
    private static Document anyStep(Document condition) {
        return new Document("$anyElementTrue", List.of(new Document("$map", new Document("input",
                new Document("$ifNull", List.of("$steps", List.of()))).append("as", "s").append("in", condition))));
    }

    private static Document countIf(Object condition) {
        return sumIf(condition, 1);
    }

    private static Document sumIf(Object condition, Object value) {
        return new Document("$sum", new Document("$cond", List.of(condition, value, 0)));
    }

    // Числото или 0 (липсващо поле или текст)
    private static Document number(String field) {
        return new Document("$cond", List.of(new Document("$isNumber", field), field, 0));
    }

    private static long asLong(Document d, String key) {
        return d.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static double asDouble(Document d, String key) {
        return d.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    private String collection(String hotelId) {
        String collection = COLLECTION_PREFIX + hotelId;
        ensureIndexes(collection);
        return collection;
    }

    private void ensureIndexes(String collection) {
        if (indexedCollections.contains(collection)) {
            return;
        }
        try {
            mongoTemplate.indexOps(collection).ensureIndex(new Index()
                    .on("timestamp", Sort.Direction.ASC)
                    .expire(RETENTION)
                    .named("timestamp_ttl"));
        } catch (Exception e) {
            // Напр. вече има индекс по timestamp с други настройки – логът пак се записва
            System.err.println("Could not create TTL index on " + collection + ": " + e.getMessage());
        }
        try {
            // Всяка стъпка търси записа на действието по flowId
            mongoTemplate.indexOps(collection).ensureIndex(new Index()
                    .on("flowId", Sort.Direction.ASC)
                    .sparse()
                    .named("flowId"));
        } catch (Exception e) {
            System.err.println("Could not create flowId index on " + collection + ": " + e.getMessage());
        }
        indexedCollections.add(collection);
    }
}
