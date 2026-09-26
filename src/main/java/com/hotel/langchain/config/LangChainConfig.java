package com.hotel.langchain.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChainConfig {

    // Общо хранилище: така и ChatHistoryService може да допише съобщения в паметта на разговора
    // (напр. отказ на резервация от бутон), а асистентът да ги види при следващото извикване
    // Най-много толкова разговора в RAM (влезли потребители + всеки гост поотделно)
    private static final int MAX_CONVERSATIONS = 5000;

    @Bean
    public ChatMemoryStore chatMemoryStore() {
        return new BoundedChatMemoryStore(MAX_CONVERSATIONS);
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore chatMemoryStore) {
        // Отделна памет за всеки разговор (виж ChatHistoryService.memoryId), пази последните 10 съобщения
        return memoryId -> new UserFirstChatMemory(MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .chatMemoryStore(chatMemoryStore)
                .build());
    }

}
