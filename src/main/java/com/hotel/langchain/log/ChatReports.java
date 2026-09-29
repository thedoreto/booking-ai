package com.hotel.langchain.log;

import java.time.Instant;
import java.util.List;

// Отчетите от logs_<hotelId> за админ панела (ChatLogRepository). Броят се стъпките: отделният запис е една стъпка,
// действието (нова резервация, отказ) – толкова, колкото стъпки има. Така въпрос, който отваря календара, пак е въпрос.
public final class ChatReports {

    private ChatReports() {
    }

    // questions – въпроси в чата; unanswered – от тях без отговор в знанията (Gemini е сложил Assistant.NO_INFO_MARKER); buttons – натиснати бутони; bookings/bookedRooms/bookedTotal – успешни резервации,
    // стаите в тях и сумата; cancellations/canceledTotal – успешни откази; users – различни влезли потребители;
    // steps / guestSteps – всички стъпки и тези от гости (без userId)
    public record Summary(long questions, long unanswered, long buttons, long bookings, long bookedRooms, double bookedTotal,
                          long cancellations, double canceledTotal, long users, long steps, long guestSteps) {}

    // Новите резервации (записите new_booking), започнати в периода. Всеки запис се брои веднъж на всеки ред:
    // searched – търсил с дати; roomsShown – видял свободни стаи; booked – резервирал;
    // onlyNoRooms – търсил, но нито веднъж не е имало стаи; bookingFailed – опитал, но нито една резервация не е минала
    public record BookingFunnel(long started, long fromChat, long fromButton, long searched, long roomsShown,
                                long booked, long onlyNoRooms, long bookingFailed) {}

    // Търсене без свободни стаи – еднакви дати и тип стая заедно; roomType null – всички типове
    public record NoRoomsSearch(String startDate, String endDate, String roomType, long count) {}

    // Gemini в чата: steps – всички стъпки, withGemini – тези, за които е викан Gemini (gemini.calls > 0);
    // limitMinute / limitDay – колко пъти хотелът е стигнал лимита си (в логовете е само първият отказ в минутата/деня)
    public record GeminiShare(long steps, long withGemini, long limitMinute, long limitDay) {}

    // Въпрос в чата с оценките на знанията, които RAG е подал на Gemini (details.knowledgeScores, в реда на търсенето);
    // outcome – no_result, ако Gemini няма отговора в знанията
    public record ScoredQuestion(Instant at, String question, List<Double> scores, String outcome) {}

    // Натиснат бутон: label – последният записан надпис (на езика по подразбиране на хотела)
    public record ButtonUsage(String shortcutId, String label, long count, long noResult) {}
}
