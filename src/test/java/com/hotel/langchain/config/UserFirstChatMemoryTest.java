package com.hotel.langchain.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserFirstChatMemoryTest {

    private static final ToolExecutionRequest TOOL_CALL = ToolExecutionRequest.builder()
            .id("1").name("showMyBookings").arguments("{}").build();

    private final MessageWindowChatMemory window = MessageWindowChatMemory.withMaxMessages(10);
    private final UserFirstChatMemory memory = new UserFirstChatMemory(window);

    @Test
    void historyStartsWithUserMessageAfterTheSystemOne() {
        // Така остава историята, когато прозорецът е изтрил потребителското съобщение пред function call-а
        SystemMessage system = SystemMessage.from("Ти си асистент на хотела.");
        UserMessage user = UserMessage.from("Какви стаи имате?");
        AiMessage reply = AiMessage.from("Единични и двойни.");
        window.add(system);
        window.add(AiMessage.from(TOOL_CALL));
        window.add(ToolExecutionResultMessage.from(TOOL_CALL, "[]"));
        window.add(AiMessage.from("Нямате резервации."));
        window.add(user);
        window.add(reply);

        assertThat(memory.messages()).containsExactly(system, user, reply);
    }

    @Test
    void toolCallsAfterTheUserMessageStay() {
        UserMessage user = UserMessage.from("Покажи резервациите ми");
        AiMessage call = AiMessage.from(TOOL_CALL);
        ToolExecutionResultMessage result = ToolExecutionResultMessage.from(TOOL_CALL, "[]");
        window.add(user);
        window.add(call);
        window.add(result);

        assertThat(memory.messages()).containsExactly(user, call, result);
    }

    @Test
    void addAndClearGoToTheWrappedMemory() {
        memory.add(UserMessage.from("Здравей"));
        assertThat(window.messages()).hasSize(1);

        memory.clear();
        assertThat(window.messages()).isEmpty();
    }
}
