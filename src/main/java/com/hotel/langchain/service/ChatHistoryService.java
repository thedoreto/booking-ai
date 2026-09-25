package com.hotel.langchain.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.stereotype.Service;

// Паметта на разговора с асистента (по hotelId:userId).
// Действията през бутоните минават без LLM – тук ги записваме, за да знае асистентът за тях.
@Service
public class ChatHistoryService {

    private final ChatMemoryProvider chatMemoryProvider;

    public ChatHistoryService(ChatMemoryProvider chatMemoryProvider) {
        this.chatMemoryProvider = chatMemoryProvider;
    }

    public static String memoryId(String hotelId, String userId) {
        return hotelId + ":" + (userId != null && !userId.isBlank() ? userId : "anonymous");
    }

    // Записва действието като двойка съобщения – потребител и асистент
    public void record(String hotelId, String userId, String userText, String assistantText) {
        try {
            ChatMemory memory = chatMemoryProvider.get(memoryId(hotelId, userId));
            memory.add(UserMessage.from(userText));
            memory.add(AiMessage.from(assistantText));
        } catch (Exception e) {
            // Паметта е помощна – грешка тук не трябва да проваля самото действие
            System.err.println("Could not record chat history for hotelId=" + hotelId + ", userId=" + userId + ": " + e);
        }
    }
}
