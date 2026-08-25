package com.hotel.langchain.controller;

import com.hotel.langchain.assistant.Assistant;
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
    public record ChatRequest(List<Message> messages) {}
    public record NewChatResponse(String reply) {}

    @PostMapping("/chat")
    public NewChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return new NewChatResponse("Липсват съобщения.");
        }

        // Взимаме последното съобщение на потребителя
        String userText = request.messages().get(request.messages().size() - 1).content();

        try {
            // Пращаме го към AI услугата (тя автоматично ползва паметта и системния промпт!)
            String aiReply = assistant.chat(userText);
            return new NewChatResponse(aiReply);

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";

            // Проверяваме за 429 (Too Many Requests / Rate Limit Exceeded)
            if (errorMsg.contains("429") || errorMsg.contains("Too Many Requests") || errorMsg.contains("RESOURCE_EXHAUSTED")) {
                return new NewChatResponse("⚠️ В момента имаме твърде много заявки към системата. Моля, опитайте отново след малко!");
            }

            // Общ отговор при друга грешка
            return new NewChatResponse("Възникна техническа грешка при връзката с асистента. Моля, опитайте по-късно.");
        }
    }
}