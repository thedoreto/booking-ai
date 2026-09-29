package com.hotel.langchain.repository;

import com.hotel.langchain.log.ChatReports;
import com.hotel.langchain.model.ChatLog;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Агрегациите на отчетите срещу истинска MongoDB (вградена, без Atlas). Записите минават по същия път като
// от чата: insert за отделен запис, addFlowStep за стъпка в действие.
class ChatLogReportsTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    private static MongoClient client;
    private static ChatLogRepository repository;

    private static final Instant NOW = Instant.now();
    private static final Instant FROM = NOW.minus(Duration.ofDays(30));

    @BeforeAll
    static void startMongo() {
        mongod = Mongod.instance().start(Version.Main.V7_0);
        client = MongoClients.create("mongodb://" + mongod.current().getServerAddress());
        repository = new ChatLogRepository(new MongoTemplate(client, "reports_test"));
    }

    @AfterAll
    static void stopMongo() {
        client.close();
        mongod.close();
    }

    @Test
    void summaryCountsQuestionsAndButtonsAlsoWhenTheyStartABooking() {
        String hotel = hotel();
        repository.insert(hotel, single("chat", "ok", null, Map.of()));
        repository.insert(hotel, single("chat", "rejected", "user-1", Map.of()));
        repository.insert(hotel, single("chat", "no_result", null, Map.of()));
        repository.insert(hotel, single("shortcut", "ok", null, Map.of("shortcutId", "parking")));
        // Въпрос, който отваря календара, после резервация на 2 стаи
        String flow = UUID.randomUUID().toString();
        repository.addFlowStep(hotel, flow("new_booking", flow, "chat", "user-1"), step("chat", "ok", Map.of()));
        repository.addFlowStep(hotel, flow("new_booking", flow, "chat", "user-1"), step("search", "ok", Map.of()));
        repository.addFlowStep(hotel, flow("new_booking", flow, "chat", "user-1"),
                step("booking", "ok", Map.of("bookingIds", List.of("b1", "b2"), "totalPrice", 350.0)));
        // Неуспешна резервация не се брои
        String failed = UUID.randomUUID().toString();
        repository.addFlowStep(hotel, flow("new_booking", failed, "button", "user-2"),
                step("booking", "error", Map.of("bookingIds", List.of("b3"), "totalPrice", 999.0)));
        // Бутон „Моите резервации“ и отказ
        String cancel = UUID.randomUUID().toString();
        repository.addFlowStep(hotel, flow("cancel_booking", cancel, "button", "user-2"),
                step("shortcut", "ok", Map.of("shortcutId", "my-bookings")));
        repository.addFlowStep(hotel, flow("cancel_booking", cancel, "button", "user-2"),
                step("cancel", "ok", Map.of("totalPrice", 120)));
        // Стар запис – извън периода
        ChatLog old = single("chat", "ok", "user-3", Map.of());
        old.setTimestamp(NOW.minus(Duration.ofDays(40)));
        repository.insert(hotel, old);

        ChatReports.Summary summary = repository.summary(hotel, FROM);

        assertThat(summary.questions()).isEqualTo(4);
        assertThat(summary.unanswered()).isEqualTo(1);
        assertThat(summary.buttons()).isEqualTo(2);
        assertThat(summary.bookings()).isEqualTo(1);
        assertThat(summary.bookedRooms()).isEqualTo(2);
        assertThat(summary.bookedTotal()).isEqualTo(350.0);
        assertThat(summary.cancellations()).isEqualTo(1);
        assertThat(summary.canceledTotal()).isEqualTo(120.0);
        assertThat(summary.users()).isEqualTo(2);
        assertThat(summary.steps()).isEqualTo(10);
        assertThat(summary.guestSteps()).isEqualTo(3);
    }

    @Test
    void emptyPeriodGivesZeros() {
        String hotel = hotel();

        assertThat(repository.summary(hotel, FROM))
                .isEqualTo(new ChatReports.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(repository.bookingFunnel(hotel, FROM))
                .isEqualTo(new ChatReports.BookingFunnel(0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(repository.noRoomsSearches(hotel, FROM, 20)).isEmpty();
        assertThat(repository.buttonUsage(hotel, FROM)).isEmpty();
    }

    @Test
    void funnelCountsEachBookingOncePerRow() {
        String hotel = hotel();
        // 1: от чата – календар, без стаи, после стаи и резервация (два пъти търсене – брои се веднъж)
        String booked = UUID.randomUUID().toString();
        addSteps(hotel, "new_booking", booked, "chat",
                step("chat", "ok", Map.of()), step("search", "no_result", Map.of()), step("search", "ok", Map.of()),
                step("booking", "ok", Map.of()));
        // 2: от бутон – само без стаи
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "button",
                step("shortcut", "ok", Map.of()), step("search", "no_result", Map.of()));
        // 3: от бутон – стаи, резервацията не минава
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "button",
                step("shortcut", "ok", Map.of()), step("search", "ok", Map.of()), step("booking", "error", Map.of()));
        // 4: от бутон – само календар
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "button", step("shortcut", "ok", Map.of()));
        // Отказът не е нова резервация
        addSteps(hotel, "cancel_booking", UUID.randomUUID().toString(), "button", step("my_bookings", "ok", Map.of()));

        assertThat(repository.bookingFunnel(hotel, FROM))
                .isEqualTo(new ChatReports.BookingFunnel(4, 1, 3, 3, 2, 1, 1, 1));
    }

    @Test
    void noRoomsSearchesAreGroupedByDatesAndRoomType() {
        String hotel = hotel();
        Map<String, Object> july = Map.of("startDate", "2026-07-01", "endDate", "2026-07-05", "roomType", "DOUBLE");
        Map<String, Object> julyAnyType = Map.of("startDate", "2026-07-01", "endDate", "2026-07-05");
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "button",
                step("search", "no_result", july), step("search", "no_result", julyAnyType));
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "chat",
                step("search", "no_result", july), step("search", "ok", Map.of("startDate", "2026-08-01")));

        assertThat(repository.noRoomsSearches(hotel, FROM, 20)).containsExactly(
                new ChatReports.NoRoomsSearch("2026-07-01", "2026-07-05", "DOUBLE", 2),
                new ChatReports.NoRoomsSearch("2026-07-01", "2026-07-05", null, 1));
        assertThat(repository.noRoomsSearches(hotel, FROM, 1)).hasSize(1);
    }

    @Test
    void buttonsAreCountedWithTheLatestLabel() {
        String hotel = hotel();
        ChatLog oldLabel = single("shortcut", "ok", null, Map.of("shortcutId", "parking", "label", "Паркинг"));
        oldLabel.setTimestamp(NOW.minus(Duration.ofDays(2)));
        repository.insert(hotel, oldLabel);
        repository.insert(hotel, single("shortcut", "no_result", null, Map.of("shortcutId", "parking", "label", "Паркинг и гараж")));
        repository.insert(hotel, single("shortcut", "ok", null, Map.of("shortcutId", "wifi", "label", "Wi-Fi")));
        // Бутон, който отваря календара – първа стъпка в новата резервация
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "button",
                step("shortcut", "ok", Map.of("shortcutId", "booking", "label", "Резервация")));
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "button",
                step("shortcut", "ok", Map.of("shortcutId", "booking", "label", "Резервация")));

        assertThat(repository.buttonUsage(hotel, FROM)).containsExactly(
                new ChatReports.ButtonUsage("booking", "Резервация", 2, 0),
                new ChatReports.ButtonUsage("parking", "Паркинг и гараж", 2, 1),
                new ChatReports.ButtonUsage("wifi", "Wi-Fi", 1, 0));
    }

    @Test
    void geminiShareCountsStepsWithGeminiCallsAndHotelLimits() {
        String hotel = hotel();
        // Въпрос с Gemini, бутон без Gemini, въпрос, при който Gemini е върнал само грешка
        ChatLog question = single("chat", "ok", null, Map.of());
        question.setGemini(gemini(2));
        repository.insert(hotel, question);
        repository.insert(hotel, single("shortcut", "ok", null, Map.of()));
        ChatLog failed = single("chat", "error", null, Map.of());
        failed.setErrorType("GEMINI_OVERLOADED_503");
        failed.setGemini(gemini(1));
        repository.insert(hotel, failed);
        // Откази заради лимита на хотела – без Gemini
        ChatLog minute = single("chat", "rejected", null, Map.of());
        minute.setErrorType("HOTEL_LIMIT_MINUTE");
        repository.insert(hotel, minute);
        ChatLog day = single("chat", "rejected", null, Map.of());
        day.setErrorType("HOTEL_LIMIT_DAY");
        repository.insert(hotel, day);
        // Въпрос, който отваря календара (с Gemini), после търсене без Gemini
        ChatLog.Step calendar = step("chat", "ok", Map.of());
        calendar.setGemini(gemini(1));
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "chat", calendar, step("search", "ok", Map.of()));

        assertThat(repository.geminiShare(hotel, FROM)).isEqualTo(new ChatReports.GeminiShare(7, 3, 1, 1));
        assertThat(repository.geminiShare(hotel(), FROM)).isEqualTo(new ChatReports.GeminiShare(0, 0, 0, 0));
    }

    @Test
    void recentQuestionsHaveTheScoresNewestFirst() {
        String hotel = hotel();
        ChatLog older = single("chat", "no_result", null, Map.of("knowledgeScores", List.of(0.61, 0.6)));
        older.setUserMessage("Имате ли басейн?");
        older.setTimestamp(NOW.minus(Duration.ofHours(2)));
        repository.insert(hotel, older);
        // Въпрос, който отваря календара – стъпка в действие
        ChatLog.Step calendar = step("chat", "ok", Map.of("knowledgeScores", List.of(0.7)));
        calendar.setUserMessage("Искам стая за петък");
        addSteps(hotel, "new_booking", UUID.randomUUID().toString(), "chat", calendar);
        // Без оценки (отказан, без RAG) и бутон – не са в списъка
        repository.insert(hotel, single("chat", "rejected", null, Map.of()));
        repository.insert(hotel, single("shortcut", "ok", null, Map.of("knowledgeScores", List.of(0.9))));

        List<ChatReports.ScoredQuestion> questions = repository.recentQuestions(hotel, FROM, 20);

        assertThat(questions).extracting(ChatReports.ScoredQuestion::question)
                .containsExactly("Искам стая за петък", "Имате ли басейн?");
        assertThat(questions.get(1).scores()).containsExactly(0.61, 0.6);
        assertThat(questions.get(1).outcome()).isEqualTo("no_result");
        assertThat(repository.recentQuestions(hotel, FROM, 1)).hasSize(1);
    }

    private static ChatLog.Gemini gemini(int calls) {
        ChatLog.Gemini gemini = new ChatLog.Gemini();
        gemini.setCalls(calls);
        gemini.setErrors(0);
        gemini.setInputTokens(100);
        gemini.setOutputTokens(10);
        return gemini;
    }

    private static String hotel() {
        return "h" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void addSteps(String hotel, String type, String flowId, String startedBy, ChatLog.Step... steps) {
        for (ChatLog.Step step : steps) {
            repository.addFlowStep(hotel, flow(type, flowId, startedBy, "user-1"), step);
        }
    }

    private static ChatLog single(String type, String outcome, String userId, Map<String, Object> details) {
        ChatLog log = new ChatLog();
        log.setTimestamp(NOW);
        log.setType(type);
        log.setOutcome(outcome);
        log.setUserId(userId);
        log.setDetails(details.isEmpty() ? null : new LinkedHashMap<>(details));
        return log;
    }

    private static ChatLog flow(String type, String flowId, String startedBy, String userId) {
        ChatLog flow = new ChatLog();
        flow.setFlowId(flowId);
        flow.setType(type);
        flow.setStartedBy(startedBy);
        flow.setUserId(userId);
        flow.setStatus("any");
        return flow;
    }

    private static ChatLog.Step step(String step, String outcome, Map<String, Object> details) {
        ChatLog.Step s = new ChatLog.Step();
        s.setStep(step);
        s.setAt(NOW);
        s.setOutcome(outcome);
        s.setDetails(details.isEmpty() ? null : new LinkedHashMap<>(details));
        return s;
    }
}
