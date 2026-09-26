package com.hotel.langchain.service;

import com.hotel.langchain.context.ChatUser;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Паметта на разговора с асистента: по хотел и токен на влезлия потребител, а за гост – по sessionId от UI.
// Действията през бутоните минават без LLM – тук ги записваме, за да знае асистентът за тях.
@Service
public class ChatHistoryService {

    private final ChatMemoryProvider chatMemoryProvider;

    public ChatHistoryService(ChatMemoryProvider chatMemoryProvider) {
        this.chatMemoryProvider = chatMemoryProvider;
    }

    // sessionId – нов при всяко отваряне на чата в UI (UUID). Гост без валиден sessionId получава памет
    // само за тази заявка – никога обща с други гости.
    public static String memoryId(String hotelId, ChatUser user, String sessionId) {
        if (user != null) {
            return hotelId + ":user:" + user.memoryKey();
        }
        String session = isValidSessionId(sessionId) ? sessionId : UUID.randomUUID().toString();
        return hotelId + ":guest:" + session;
    }

    private static boolean isValidSessionId(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        try {
            return UUID.fromString(sessionId).toString().equalsIgnoreCase(sessionId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Записва действието като двойка съобщения – потребител и асистент
    public void record(String hotelId, ChatUser user, String userText, String assistantText) {
        try {
            // Записват се само действия на влязъл потребител (резервация, отказ)
            ChatMemory memory = chatMemoryProvider.get(memoryId(hotelId, user, null));
            memory.add(UserMessage.from(userText));
            memory.add(AiMessage.from(assistantText));
        } catch (Exception e) {
            // Паметта е помощна – грешка тук не трябва да проваля самото действие
            System.err.println("Could not record chat history for hotelId=" + hotelId + ": " + e);
        }
    }
}
