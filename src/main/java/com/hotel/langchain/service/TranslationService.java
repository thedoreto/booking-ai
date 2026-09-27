package com.hotel.langchain.service;

import com.hotel.langchain.model.Translation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Преводите от колекция translations (модел Translation), общи за всички хотели.
// message – съобщение от кода по key; translate – текст отвън (бекенд, бутон), търсен по съдържанието си.
// Пазят се в паметта и се презареждат на REFRESH_AFTER – нов превод в Mongo влиза без рестарт.
@Service
public class TranslationService {

    // Когато съобщението няма превод на поискания език
    static final String FALLBACK_LANGUAGE = "bg";
    private static final Duration REFRESH_AFTER = Duration.ofMinutes(5);

    private record Loaded(Map<String, Map<String, String>> byKey, Map<String, Map<String, String>> byText) {}

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private volatile Loaded loaded = new Loaded(Map.of(), Map.of());
    private volatile Instant loadedAt;

    @Autowired
    public TranslationService(MongoTemplate mongoTemplate) {
        this(mongoTemplate, Clock.systemUTC());
    }

    TranslationService(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    // Съобщението на езика (иначе на FALLBACK_LANGUAGE) с {име} от params. Без запис – самият key,
    // за да се види в чата, че преводът липсва.
    public String message(String key, String language, Map<String, ?> params) {
        Map<String, String> texts = key == null ? null : current().byKey().get(key);
        String text = texts == null ? null
                : texts.getOrDefault(language == null ? FALLBACK_LANGUAGE : language, texts.get(FALLBACK_LANGUAGE));
        if (text == null) {
            System.err.println("Missing translation: key=" + key + ", language=" + language);
            return key;
        }
        for (Map.Entry<String, ?> param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", String.valueOf(param.getValue()));
        }
        return text;
    }

    public String message(String key, String language) {
        return message(key, language, Map.of());
    }

    // Всички съобщения с ключ, започващ с prefix (напр. "ui." – текстовете на прозореца на чата), на езика
    // (иначе на FALLBACK_LANGUAGE); {име} остават за UI
    public Map<String, String> messagesWithPrefix(String prefix, String language) {
        Map<String, String> result = new HashMap<>();
        current().byKey().forEach((key, texts) -> {
            if (key.startsWith(prefix)) {
                String text = texts.getOrDefault(language == null ? FALLBACK_LANGUAGE : language, texts.get(FALLBACK_LANGUAGE));
                if (text != null) {
                    result.put(key, text);
                }
            }
        });
        return result;
    }

    // Текстът на езика, ако някой запис го съдържа на който и да е език; иначе – текстът, както е дошъл
    public String translate(String text, String language) {
        if (text == null) {
            return null;
        }
        Map<String, String> texts = current().byText().get(text.trim());
        String translated = texts == null || language == null ? null : texts.get(language);
        return translated != null ? translated : text;
    }

    private Loaded current() {
        Instant now = clock.instant();
        if (loadedAt == null || !loadedAt.plus(REFRESH_AFTER).isAfter(now)) {
            reload(now);
        }
        return loaded;
    }

    private synchronized void reload(Instant now) {
        // Друга нишка може вече да е заредила преводите
        if (loadedAt != null && loadedAt.plus(REFRESH_AFTER).isAfter(now)) {
            return;
        }
        try {
            Map<String, Map<String, String>> byKey = new HashMap<>();
            Map<String, Map<String, String>> byText = new HashMap<>();
            for (Translation translation : mongoTemplate.findAll(Translation.class)) {
                Map<String, String> texts = clean(translation.getTexts());
                if (texts.isEmpty()) {
                    System.err.println("Translation without texts: _id=" + translation.getId());
                    continue;
                }
                String key = translation.getKey();
                if (key != null && !key.isBlank()) {
                    if (byKey.putIfAbsent(key.trim(), texts) != null) {
                        System.err.println("Duplicate translation key " + key + ": _id=" + translation.getId());
                    }
                    continue;
                }
                for (String text : texts.values()) {
                    // При същия текст в два записа печели първият
                    byText.putIfAbsent(text, texts);
                }
            }
            loaded = new Loaded(Map.copyOf(byKey), Map.copyOf(byText));
        } catch (Exception e) {
            // Остават старите преводи; следващият опит – след REFRESH_AFTER
            System.err.println("Could not load translations from Mongo: " + e);
        }
        loadedAt = now;
    }

    // Без празни езици и текстове; текстът – без интервали в началото и края
    private static Map<String, String> clean(Map<String, String> texts) {
        Map<String, String> result = new HashMap<>();
        if (texts != null) {
            texts.forEach((language, text) -> {
                if (language != null && !language.isBlank() && text != null && !text.isBlank()) {
                    result.put(language.trim().toLowerCase(Locale.ROOT), text.trim());
                }
            });
        }
        return Map.copyOf(result);
    }
}
