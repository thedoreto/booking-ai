package com.hotel.langchain.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChainConfig {

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        // Отделна памет за всеки разговор (hotelId:userId), пази последните 10 съобщения
        return memoryId -> new UserFirstChatMemory(MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(10)
                .build());
    }

}
