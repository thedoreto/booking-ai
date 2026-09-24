package com.hotel.langchain.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.base.model}")
    private String baseModel;

    @Bean
    ChatLanguageModel chatLanguageModel() {
        ChatLanguageModel gemini = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(baseModel)
                .maxRetries(1) // повторните опити са в RetryingChatLanguageModel
                .build();
        // При 503 от Gemini: нов опит след 2s, 5s и 10s
        ChatLanguageModel retrying = new RetryingChatLanguageModel(gemini, 2_000, 5_000, 10_000);
        // Без излишно извикване към Gemini, след като tool е поискал календара
        return new DatePickerShortCircuitChatModel(retrying);
    }

    @Bean
    EmbeddingModel embeddingModel() {
        return GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-embedding-001")
                .build();
    }
}