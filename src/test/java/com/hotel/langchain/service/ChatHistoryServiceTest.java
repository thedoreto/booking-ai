package com.hotel.langchain.service;

import com.hotel.langchain.context.ChatUser;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// Два госта никога не делят памет: всеки е по своя sessionId, а без валиден sessionId – памет само за заявката
class ChatHistoryServiceTest {

    private static final String HOTEL = "seven_stars";

    @Test
    void eachGuestHasOwnMemory() {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        assertThat(ChatHistoryService.memoryId(HOTEL, null, first)).isEqualTo(HOTEL + ":guest:" + first);
        assertThat(ChatHistoryService.memoryId(HOTEL, null, first))
                .isNotEqualTo(ChatHistoryService.memoryId(HOTEL, null, second));
    }

    @Test
    void guestWithoutValidSessionIdNeverSharesMemory() {
        assertThat(ChatHistoryService.memoryId(HOTEL, null, null))
                .isNotEqualTo(ChatHistoryService.memoryId(HOTEL, null, null));
        assertThat(ChatHistoryService.memoryId(HOTEL, null, "anonymous"))
                .isNotEqualTo(ChatHistoryService.memoryId(HOTEL, null, "anonymous"));
    }

    @Test
    void loggedInUserMemoryDependsOnUserIdNotOnSessionId() {
        ChatUser user = new ChatUser("user-1", "token-1");

        assertThat(ChatHistoryService.memoryId(HOTEL, user, UUID.randomUUID().toString()))
                .isEqualTo(ChatHistoryService.memoryId(HOTEL, user, null))
                .isEqualTo(HOTEL + ":user:user-1");
    }

    @Test
    void memorySurvivesNewLogin() {
        // userId е проверен (ChatUserResolver), затова новият токен след вход продължава същия разговор
        assertThat(ChatHistoryService.memoryId(HOTEL, new ChatUser("user-1", "token-after-login"), null))
                .isEqualTo(ChatHistoryService.memoryId(HOTEL, new ChatUser("user-1", "token-1"), null));
    }

    @Test
    void buttonActionIsRecordedInTheMemoryOfTheUser() {
        Map<Object, ChatMemory> memories = new HashMap<>();
        ChatHistoryService service = new ChatHistoryService(
                id -> memories.computeIfAbsent(id, key -> MessageWindowChatMemory.withMaxMessages(10)));
        ChatUser user = new ChatUser("user-1", "token-1");

        service.record(HOTEL, user, "Резервирай стая №12 от 30.10.2099 до 02.11.2099.", "Резервацията е потвърдена.");

        // Същата памет, която ползва и чатът с Gemini за този потребител
        assertThat(memories.get(HOTEL + ":user:user-1").messages()).containsExactly(
                UserMessage.from("Резервирай стая №12 от 30.10.2099 до 02.11.2099."),
                AiMessage.from("Резервацията е потвърдена."));
    }

    @Test
    void memoryErrorDoesNotFailTheAction() {
        ChatHistoryService service = new ChatHistoryService(id -> {
            throw new IllegalStateException("memory store down");
        });

        assertThatCode(() -> service.record(HOTEL, new ChatUser("user-1", "t"), "Откажи резервацията.", "Отказана."))
                .doesNotThrowAnyException();
    }
}
