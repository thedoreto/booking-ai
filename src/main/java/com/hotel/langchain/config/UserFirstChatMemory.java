package com.hotel.langchain.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * Обвивка около ChatMemory, която гарантира, че историята (след system съобщението) започва с UserMessage.
 * MessageWindowChatMemory изтрива най-старите съобщения и може да остави AiMessage с function call в началото,
 * а Gemini връща 400 "function call turn comes immediately after a user turn".
 */
public class UserFirstChatMemory implements ChatMemory {

    private final ChatMemory delegate;

    public UserFirstChatMemory(ChatMemory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(message);
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>();
        boolean userSeen = false;
        for (ChatMessage message : delegate.messages()) {
            if (message instanceof UserMessage) {
                userSeen = true;
            }
            // Пропускаме AI/tool съобщенията, останали без предхождащото ги потребителско съобщение
            if (userSeen || message instanceof SystemMessage) {
                result.add(message);
            }
        }
        return result;
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
