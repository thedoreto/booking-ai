package com.hotel.admin.service;

import com.hotel.admin.service.SuggestionAnalyzer.AnalysisFailedException;
import com.hotel.admin.service.SuggestionAnalyzer.ButtonRef;
import com.hotel.admin.service.SuggestionAnalyzer.Input;
import com.hotel.admin.service.SuggestionAnalyzer.KnowledgeRef;
import com.hotel.langchain.log.ChatLogService;
import com.hotel.langchain.log.ChatReports.QuestionCount;
import com.hotel.langchain.model.GeminiUsage;
import com.hotel.langchain.model.SuggestionAnalysis;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionAnalyzerTest {

    private static final Input INPUT = new Input("Български",
            List.of(new QuestionCount("Имате ли басейн?", 4)),
            List.of(new QuestionCount("Има ли паркинг?", 7)),
            List.of(new KnowledgeRef("k1", "Паркинг", "Удобства")),
            List.of(new ButtonRef("Закуска", List.of("k2"))),
            List.of("Липсващо знание: Спа (отхвърлено)"));

    private final ChatLanguageModel model = mock(ChatLanguageModel.class);
    private final ChatLogService chatLogService = mock(ChatLogService.class);
    private final SuggestionAnalyzer analyzer = new SuggestionAnalyzer(model, "gemini-test", chatLogService);

    @Test
    void oneCallWithEverythingTheModelNeedsAndTheSuggestionsAreRead() {
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("""
                {"missingKnowledge": [{"topic": "Басейн", "questions": ["Имате ли басейн?"], "count": 4,
                                       "draft": "Басейнът работи от [час] до [час]."}],
                 "newButtons": [{"label": "Паркинг", "knowledgeIds": ["k1"], "questions": ["Има ли паркинг?"], "count": 7}]}"""));

        SuggestionAnalyzer.Result result = analyzer.analyze("40_robbers", INPUT);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        String prompt = ((UserMessage) request.getValue().messages().get(0)).singleText();
        assertThat(prompt).contains("езика Български", "- Имате ли басейн? (4)", "- Има ли паркинг? (7)",
                "- k1 – Паркинг – Удобства", "- Закуска → [k2]", "- Липсващо знание: Спа (отхвърлено)", "[час]");

        assertThat(result.items()).hasSize(2);
        SuggestionAnalysis.Item missing = result.items().get(0);
        assertThat(missing.getType()).isEqualTo(SuggestionAnalysis.MISSING_KNOWLEDGE);
        assertThat(missing.getTopic()).isEqualTo("Басейн");
        assertThat(missing.getDraft()).isEqualTo("Басейнът работи от [час] до [час].");
        assertThat(missing.getCount()).isEqualTo(4);
        SuggestionAnalysis.Item button = result.items().get(1);
        assertThat(button.getType()).isEqualTo(SuggestionAnalysis.NEW_BUTTON);
        assertThat(button.getLabel()).isEqualTo("Паркинг");
        assertThat(button.getKnowledgeIds()).containsExactly("k1");
        assertThat(result.inputTokens()).isEqualTo(900);
        assertThat(result.outputTokens()).isEqualTo(120);

        ArgumentCaptor<GeminiUsage> usage = ArgumentCaptor.forClass(GeminiUsage.class);
        verify(chatLogService).geminiUsage(eq("40_robbers"), usage.capture());
        assertThat(usage.getValue().getSource()).isEqualTo(GeminiUsage.ADMIN_ANALYSIS);
        assertThat(usage.getValue().getInputTokens()).isEqualTo(900);
    }

    @Test
    void geminiErrorOrNotJsonFails() {
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("503"));
        assertThatThrownBy(() -> analyzer.analyze("40_robbers", INPUT)).isInstanceOf(AnalysisFailedException.class);
        ArgumentCaptor<GeminiUsage> usage = ArgumentCaptor.forClass(GeminiUsage.class);
        verify(chatLogService).geminiUsage(eq("40_robbers"), usage.capture());
        assertThat(usage.getValue().getErrors()).isEqualTo(1);

        ChatLanguageModel notJson = mock(ChatLanguageModel.class);
        when(notJson.chat(any(ChatRequest.class))).thenReturn(answer("Ето предложенията: басейн."));
        assertThatThrownBy(() -> new SuggestionAnalyzer(notJson, "gemini-test", chatLogService).analyze("40_robbers", INPUT))
                .isInstanceOf(AnalysisFailedException.class);
    }

    @Test
    void emptyListsAndFencesAreFine() {
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("```json\n{\"missingKnowledge\": [], \"newButtons\": []}\n```"));

        assertThat(analyzer.analyze("40_robbers", INPUT).items()).isEmpty();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(900, 120)).build();
    }
}
