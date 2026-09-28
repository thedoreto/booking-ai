package com.hotel.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.langchain.config.RetryingChatLanguageModel;
import com.hotel.langchain.service.HotelLanguages.Language;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Превод на знание с Gemini – само от админ панела, никога в чата. Едно извикване за всички поискани езици,
// отговорът е JSON { "<код>": "<превод>" }. Моделът е отделен от този на чата (без tools, памет, логове и лимита
// на чата); повторни опити при 503 – както в чата.
@Service
public class KnowledgeTranslator {

    // Gemini не преведе (грешка, празен или непълен отговор) – нищо не се записва
    public static class TranslationFailedException extends RuntimeException {
        public TranslationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String PROMPT = """
            Преведи текста за хотел от %s на следните езици: %s.
            Запази смисъла, числата, цените, часовете, имената, адресите и текстовете в кавички. Не добавяй нищо.
            Върни само JSON обект, в който ключовете са кодовете на езиците, а стойностите – преводите: %s

            Текст:
            %s""";

    private final ChatLanguageModel model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public KnowledgeTranslator(@Value("${gemini.api.key}") String apiKey, @Value("${gemini.base.model}") String baseModel) {
        this(new RetryingChatLanguageModel(GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(baseModel)
                .responseFormat(ResponseFormat.JSON)
                .temperature(0.2)
                .maxRetries(1)
                .build(), 2_000, 5_000));
    }

    KnowledgeTranslator(ChatLanguageModel model) {
        this.model = model;
    }

    // Код на език → преводът; всички поискани езици, иначе TranslationFailedException
    public Map<String, String> translate(String text, String fromLanguageName, List<Language> targets) {
        String names = targets.stream().map(l -> l.name() + " (" + l.code() + ")").collect(Collectors.joining(", "));
        String keys = targets.stream().map(l -> "\"" + l.code() + "\"").collect(Collectors.joining(", "));
        String answer;
        try {
            answer = model.chat(PROMPT.formatted(fromLanguageName, names, keys, text));
        } catch (RuntimeException e) {
            throw new TranslationFailedException("Gemini error", e);
        }
        Map<String, String> parsed;
        try {
            parsed = objectMapper.readValue(stripFences(answer), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new TranslationFailedException("Not a JSON object: " + abbreviate(answer), e);
        }
        Map<String, String> translations = new LinkedHashMap<>();
        for (Language target : targets) {
            String translation = parsed.get(target.code());
            if (translation == null || translation.isBlank()) {
                throw new TranslationFailedException("Missing translation for " + target.code(), null);
            }
            translations.put(target.code(), translation.trim());
        }
        return translations;
    }

    // JSON режимът обикновено връща чист JSON, но понякога е в ```json ... ```
    private static String stripFences(String answer) {
        String s = answer == null ? "" : answer.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return s;
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
