package com.hotel.langchain.service;

import java.util.Map;

// Текстовете за потребителя на един хотел и един език – TranslationService.forRequest(hotelId, language).
// Хотелът и езикът се задават веднъж в началото на заявката.
public interface Texts {

    // Съобщение от кода по key с {име} от params; без превод – самият key
    String message(String key, Map<String, ?> params);

    default String message(String key) {
        return message(key, Map.of());
    }

    // Текст отвън (бекенд, бутон), търсен по съдържанието си; без превод – текстът, както е дошъл
    String translate(String text);

    // Всички съобщения с ключ, започващ с prefix (напр. "ui." – текстовете на прозореца на чата); {име} остават за UI
    Map<String, String> withPrefix(String prefix);
}
