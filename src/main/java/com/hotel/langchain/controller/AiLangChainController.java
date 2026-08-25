package com.hotel.langchain.controller;

import com.hotel.langchain.assistant.Assistant;
import com.hotel.langchain.context.TenantContext; // Импортирайте контекста
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AiLangChainController {

    private final Assistant assistant;

    public AiLangChainController(Assistant assistant) {
        this.assistant = assistant;
    }

    public record Message(String role, String content) {}
    public record ChatRequest(String hotelId, List<Message> messages) {}
    public record NewChatResponse(String reply) {}

    @PostMapping("/chat")
    public NewChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return new NewChatResponse("Липсват съобщения.");
        }

        // Взимаме последното съобщение на потребителя
        String userText = request.messages().get(request.messages().size() - 1).content();

        try {
            // 1. Подаваме hotelId (който идва от UI в JSON заявката) към ThreadLocal контекста
            if (request.hotelId() != null && !request.hotelId().isBlank()) {
                TenantContext.setHotelId(request.hotelId());
            }

            // 2. Пращаме го към AI услугата
            String aiReply = assistant.chat(userText);
            return new NewChatResponse(aiReply);

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";

            if (errorMsg.contains("429") || errorMsg.contains("Too Many Requests") || errorMsg.contains("RESOURCE_EXHAUSTED")) {
                return new NewChatResponse("⚠️ В момента имаме твърде много заявки към системата. Моля, опитайте отново след малко!");
            }

            return new NewChatResponse("Възникна техническа грешка при връзката с асистента. Моля, опитайте по-късно.");
        } finally {
            // 3. ЗАДЪЛЖИТЕЛНО чистим контекста след приключване на заявката (предотвратява течове в пула от нишки)
            TenantContext.clear();
        }
    }
}