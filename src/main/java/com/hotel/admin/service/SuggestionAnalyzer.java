package com.hotel.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.langchain.config.RetryingChatLanguageModel;
import com.hotel.langchain.log.ChatLogService;
import com.hotel.langchain.log.ChatReports.QuestionCount;
import com.hotel.langchain.model.GeminiUsage;
import com.hotel.langchain.model.SuggestionAnalysis;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Анализ на въпросите от чата с Gemini – само по бутон в админ панела (таб „Предложения“), никога в чата.
// Едно извикване: въпросите без отговор → липсващи знания (черновата е с празни места [..] вместо факти, които
// Gemini не знае); честите въпроси с отговор → бутони към съществуващи знания. Отговорът е JSON.
// Моделът е отделен от този на чата (без tools, памет, логове и лимита на чата); токените – в gemini_usage_<hotelId>
// (admin_analysis). Проверката на резултата (знанията съществуват ли и т.н.) е в AdminSuggestionService.
@Service
public class SuggestionAnalyzer {

    // Gemini не анализира (грешка или отговор, който не е JSON) – нищо не се записва
    public static class AnalysisFailedException extends RuntimeException {
        public AnalysisFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // Знание на хотела – за бутоните; title – заглавието или началото на текста
    public record KnowledgeRef(String id, String title, String category) {}

    // Бутон, който вече го има
    public record ButtonRef(String label, List<String> knowledgeIds) {}

    public record Input(String languageName, List<QuestionCount> unanswered, List<QuestionCount> answered,
                        List<KnowledgeRef> knowledge, List<ButtonRef> buttons, List<String> handled) {}

    // items – без id и status (дава ги AdminSuggestionService)
    public record Result(List<SuggestionAnalysis.Item> items, long inputTokens, long outputTokens) {}

    private static final String PROMPT = """
            Ти помагаш на администратора на хотел да подобри чат асистента си. Асистентът отговаря само от знанията на хотела.
            Отговори на езика %1$s.

            1) ЛИПСВАЩИ ЗНАНИЯ. Това са въпроси на гостите, на които асистентът НЕ е намерил отговор в знанията (брой пъти в скоби):
            %2$s
            Групирай ги по смисъл (напр. „Има ли паркинг?“ и „Къде да оставя колата?“ са една тема). За всяка тема дай:
            topic – кратко име на темата; questions – до 5 от въпросите на гостите, както са зададени; count – общо колко пъти е питано;
            draft – чернова на знание за хотела. Ти НЕ знаеш фактите за хотела: всеки факт (час, цена, да/не, име, място, правило)
            замени с празно място в квадратни скоби, напр. „Късното напускане е възможно до [час] срещу [цена].“ Не измисляй нищо.
            Пропусни поздрави, шеги и въпроси, които не са за хотела.

            2) НОВИ БУТОНИ. Бутонът показва знание директно, без асистента. Това са въпроси, на които асистентът е отговорил:
            %3$s
            Знанията на хотела (id – заглавие – категория):
            %4$s
            Предложи бутон само за тема, питана поне 3 пъти общо, и само ако отговорът е в едно или няколко от знанията по-горе.
            За всеки: label – кратък надпис на бутона (до 30 знака); knowledgeIds – id на знанията точно както са дадени;
            questions – до 5 от въпросите; count – общо колко пъти е питано. Не предлагай бутон, който вече го има:
            %5$s

            Не предлагай отново тези, които администраторът вече е разгледал:
            %6$s

            Най-много 10 липсващи знания и 10 бутона, първо най-честите. Ако няма какво да предложиш – празни списъци.
            Върни само JSON обект:
            {"missingKnowledge": [{"topic": "", "questions": [""], "count": 0, "draft": ""}],
             "newButtons": [{"label": "", "knowledgeIds": [""], "questions": [""], "count": 0}]}""";

    private final ChatLanguageModel model;
    private final String modelName;
    private final ChatLogService chatLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public SuggestionAnalyzer(@Value("${gemini.api.key}") String apiKey, @Value("${gemini.base.model}") String baseModel,
                              ChatLogService chatLogService) {
        this(new RetryingChatLanguageModel(GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(baseModel)
                .responseFormat(ResponseFormat.JSON)
                .temperature(0.2)
                .maxRetries(1)
                .build(), 2_000, 5_000), baseModel, chatLogService);
    }

    SuggestionAnalyzer(ChatLanguageModel model, String modelName, ChatLogService chatLogService) {
        this.model = model;
        this.modelName = modelName;
        this.chatLogService = chatLogService;
    }

    public Result analyze(String hotelId, Input input) {
        String prompt = PROMPT.formatted(input.languageName(),
                questions(input.unanswered()), questions(input.answered()),
                lines(input.knowledge().stream().map(k -> k.id() + " – " + k.title()
                        + (k.category() == null ? "" : " – " + k.category())).toList()),
                lines(input.buttons().stream().map(b -> b.label() + " → " + b.knowledgeIds()).toList()),
                lines(input.handled()));
        ChatResponse response;
        try {
            response = model.chat(ChatRequest.builder().messages(UserMessage.from(prompt)).build());
        } catch (RuntimeException e) {
            chatLogService.geminiUsage(hotelId, GeminiUsage.tokens(GeminiUsage.ADMIN_ANALYSIS, modelName, 1, 1, 0, 0));
            throw new AnalysisFailedException("Gemini error", e);
        }
        TokenUsage tokens = response.tokenUsage();
        long inputTokens = tokens != null && tokens.inputTokenCount() != null ? tokens.inputTokenCount() : 0;
        long outputTokens = tokens != null && tokens.outputTokenCount() != null ? tokens.outputTokenCount() : 0;
        chatLogService.geminiUsage(hotelId, GeminiUsage.tokens(GeminiUsage.ADMIN_ANALYSIS, modelName, 1, 0,
                inputTokens, outputTokens));
        String answer = response.aiMessage() != null ? response.aiMessage().text() : null;
        JsonNode json;
        try {
            json = objectMapper.readTree(stripFences(answer));
        } catch (Exception e) {
            throw new AnalysisFailedException("Not JSON: " + abbreviate(answer), e);
        }
        if (json == null || !json.isObject()) {
            throw new AnalysisFailedException("Not a JSON object: " + abbreviate(answer), null);
        }
        List<SuggestionAnalysis.Item> items = new ArrayList<>();
        for (JsonNode node : json.path("missingKnowledge")) {
            SuggestionAnalysis.Item item = item(SuggestionAnalysis.MISSING_KNOWLEDGE, node);
            item.setTopic(text(node, "topic"));
            item.setDraft(text(node, "draft"));
            items.add(item);
        }
        for (JsonNode node : json.path("newButtons")) {
            SuggestionAnalysis.Item item = item(SuggestionAnalysis.NEW_BUTTON, node);
            item.setLabel(text(node, "label"));
            item.setKnowledgeIds(texts(node.path("knowledgeIds")));
            items.add(item);
        }
        return new Result(items, inputTokens, outputTokens);
    }

    private static SuggestionAnalysis.Item item(String type, JsonNode node) {
        SuggestionAnalysis.Item item = new SuggestionAnalysis.Item();
        item.setType(type);
        item.setQuestions(texts(node.path("questions")));
        item.setCount(node.path("count").canConvertToLong() ? node.path("count").asLong() : null);
        return item;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        for (JsonNode value : array) {
            if (value.isTextual() && !value.asText().isBlank()) {
                texts.add(value.asText().trim());
            }
        }
        return texts;
    }

    // „Имате ли басейн? (4)“ – по един на ред
    private static String questions(List<QuestionCount> questions) {
        return lines(questions.stream().map(q -> q.question() + " (" + q.count() + ")").toList());
    }

    private static String lines(List<String> lines) {
        return lines.isEmpty() ? "(няма)" : lines.stream().map(l -> "- " + l).collect(Collectors.joining("\n"));
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
