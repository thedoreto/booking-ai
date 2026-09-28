package com.hotel.langchain.service;

import com.hotel.langchain.model.Translation;
import com.hotel.langchain.repository.TranslationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// Преводите на два слоя: translations_<hotelId> (на хотела) с предимство пред общата translations.
// forRequest(hotelId, language) дава Texts за една заявка:
// - message – съобщение от кода по key: за всеки език поред (поисканият, езикът по подразбиране на хотела, bg)
//   първо при хотела, после в общата; без превод – самият key;
// - translate – текст отвън (бекенд, напр. типовете стаи), търсен по съдържанието си: само на поискания език,
//   първо при хотела, после в общата; без превод – текстът, както е дошъл.
// Всяка колекция се пази в паметта и се презарежда отделно на REFRESH_AFTER – нов превод в Mongo влиза без рестарт.
@Service
public class TranslationService {

    // Когато съобщението няма превод нито на поискания език, нито на езика по подразбиране на хотела
    static final String FALLBACK_LANGUAGE = "bg";
    private static final Duration REFRESH_AFTER = Duration.ofMinutes(5);

    private final TranslationRepository translationRepository;
    private final HotelLanguages hotelLanguages;
    private final Clock clock;
    private final Layer common;
    private final Map<String, Layer> hotels = new ConcurrentHashMap<>();

    @Autowired
    public TranslationService(TranslationRepository translationRepository, HotelLanguages hotelLanguages) {
        this(translationRepository, hotelLanguages, Clock.systemUTC());
    }

    TranslationService(TranslationRepository translationRepository, HotelLanguages hotelLanguages, Clock clock) {
        this.translationRepository = translationRepository;
        this.hotelLanguages = hotelLanguages;
        this.clock = clock;
        this.common = new Layer("translations", translationRepository::findCommon);
    }

    // hotelId – проверен хотел (HotelRegistry); null – само общите преводи (напр. грешка „непознат хотел“,
    // за да не се пази слой за измислен hotelId)
    public Texts forRequest(String hotelId, String language) {
        List<Loaded> layers = new ArrayList<>();
        Set<String> languages = new LinkedHashSet<>();
        if (language != null) {
            languages.add(language);
        }
        if (hotelId != null) {
            layers.add(hotels.computeIfAbsent(hotelId, id ->
                    new Layer("translations_" + id, () -> translationRepository.findByHotelId(id))).current());
            languages.add(hotelLanguages.of(hotelId).defaultLanguage());
        }
        layers.add(common.current());
        languages.add(FALLBACK_LANGUAGE);
        return new RequestTexts(List.copyOf(layers), language, List.copyOf(languages));
    }

    private record RequestTexts(List<Loaded> layers, String language, List<String> languages) implements Texts {

        @Override
        public String message(String key, Map<String, ?> params) {
            String text = key == null ? null : find(key);
            if (text == null) {
                System.err.println("Missing translation: key=" + key + ", language=" + language);
                return key;
            }
            for (Map.Entry<String, ?> param : params.entrySet()) {
                text = text.replace("{" + param.getKey() + "}", String.valueOf(param.getValue()));
            }
            return text;
        }

        @Override
        public String translate(String text) {
            if (text == null || language == null) {
                return text;
            }
            for (Loaded layer : layers) {
                Map<String, String> texts = layer.byText().get(text.trim());
                String translated = texts == null ? null : texts.get(language);
                if (translated != null) {
                    return translated;
                }
            }
            return text;
        }

        @Override
        public Map<String, String> withPrefix(String prefix) {
            Map<String, String> result = new HashMap<>();
            for (Loaded layer : layers) {
                for (String key : layer.byKey().keySet()) {
                    if (key.startsWith(prefix) && !result.containsKey(key)) {
                        String text = find(key);
                        if (text != null) {
                            result.put(key, text);
                        }
                    }
                }
            }
            return result;
        }

        private String find(String key) {
            for (String code : languages) {
                for (Loaded layer : layers) {
                    Map<String, String> texts = layer.byKey().get(key);
                    String text = texts == null ? null : texts.get(code);
                    if (text != null) {
                        return text;
                    }
                }
            }
            return null;
        }
    }

    private record Loaded(Map<String, Map<String, String>> byKey, Map<String, Map<String, String>> byText) {
        static final Loaded EMPTY = new Loaded(Map.of(), Map.of());
    }

    // Една колекция с преводи в паметта
    private class Layer {
        private final String name;
        private final Supplier<List<Translation>> source;
        private volatile Loaded loaded = Loaded.EMPTY;
        private volatile Instant loadedAt;

        Layer(String name, Supplier<List<Translation>> source) {
            this.name = name;
            this.source = source;
        }

        Loaded current() {
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
                loaded = parse(source.get());
            } catch (Exception e) {
                // Остават старите преводи; следващият опит – след REFRESH_AFTER
                System.err.println("Could not load " + name + " from Mongo: " + e);
            }
            loadedAt = now;
        }

        private Loaded parse(List<Translation> translations) {
            Map<String, Map<String, String>> byKey = new HashMap<>();
            Map<String, Map<String, String>> byText = new HashMap<>();
            for (Translation translation : translations) {
                Map<String, String> texts = clean(translation.getTexts());
                if (texts.isEmpty()) {
                    System.err.println("Translation without texts in " + name + ": _id=" + translation.getId());
                    continue;
                }
                String key = translation.getKey();
                if (key != null && !key.isBlank()) {
                    if (byKey.putIfAbsent(key.trim(), texts) != null) {
                        System.err.println("Duplicate translation key " + key + " in " + name + ": _id=" + translation.getId());
                    }
                    continue;
                }
                for (String text : texts.values()) {
                    // При същия текст в два записа печели първият
                    byText.putIfAbsent(text, texts);
                }
            }
            return new Loaded(Map.copyOf(byKey), Map.copyOf(byText));
        }
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
