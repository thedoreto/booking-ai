package com.hotel.langchain.context;

// Влезлият потребител: JWT-то от UI (Authorization: Bearer ...) е проверено с публичния ключ на хотела
// (виж ChatUserResolver), затова id е истинското. null вместо ChatUser – гост.
// Токенът отива и през Kafka – booking-system го проверява още веднъж за действията от името на потребителя.
public record ChatUser(String id, String token) {
}
