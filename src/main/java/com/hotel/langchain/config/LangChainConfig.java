package com.hotel.langchain.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChainConfig {

    @Bean
    public ChatMemory chatMemory() {
        // Пази последните 10 съобщения от разговора в паметта
        return MessageWindowChatMemory.withMaxMessages(10);
    }

}
