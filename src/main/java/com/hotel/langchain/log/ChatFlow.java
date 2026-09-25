package com.hotel.langchain.log;

import java.util.UUID;

// Действие на госта, което минава през няколко заявки (нова резервация, отказ на резервация).
// Записва се като ЕДИН документ в logs_<hotelId>, който се допълва с всяка стъпка:
// { flowId, type, hotelId, userId, startedBy, status, timestamp (начало), updatedAt, steps: [...], gemini: {...} }
// flowId се връща на UI в data и UI го праща обратно със следващата стъпка.
public final class ChatFlow {

    // type
    public static final String NEW_BOOKING = "new_booking";
    public static final String CANCEL_BOOKING = "cancel_booking";

    // startedBy – откъде е започнало действието (не се променя от следващите стъпки)
    public static final String STARTED_BY_CHAT = "chat";
    public static final String STARTED_BY_BUTTON = "button";

    // status – състоянието след последната стъпка. Незавършено действие (без booked/canceled),
    // което не е допълвано дълго време, отчетите го броят за отказ на госта.
    public static final String DATE_PICKER = "date_picker";
    public static final String ROOMS_SHOWN = "rooms_shown";
    public static final String NO_ROOMS = "no_rooms";
    public static final String BOOKED = "booked";
    public static final String BOOKING_FAILED = "booking_failed";
    public static final String BOOKINGS_SHOWN = "bookings_shown";
    public static final String CANCELED = "canceled";
    public static final String CANCEL_FAILED = "cancel_failed";
    public static final String REJECTED = "rejected";
    public static final String ERROR = "error";

    private ChatFlow() {
    }

    // flowId от UI, ако е валиден UUID; иначе ново действие
    public static String idOrNew(String flowId) {
        if (flowId != null) {
            try {
                return UUID.fromString(flowId.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // невалиден id – започваме ново действие
            }
        }
        return UUID.randomUUID().toString();
    }

    public static String searchStatus(String outcome) {
        return switch (outcome) {
            case ChatLogEntry.OK -> ROOMS_SHOWN;
            case ChatLogEntry.NO_RESULT -> NO_ROOMS;
            case ChatLogEntry.REJECTED -> REJECTED;
            default -> ERROR;
        };
    }

    public static String bookingStatus(String outcome) {
        return ChatLogEntry.OK.equals(outcome) ? BOOKED : BOOKING_FAILED;
    }

    public static String cancelStatus(String outcome) {
        return ChatLogEntry.OK.equals(outcome) ? CANCELED : CANCEL_FAILED;
    }
}
