package com.hotel.langchain.service;

import com.hotel.langchain.context.ChatUser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    void loggedInUserMemoryDependsOnTokenNotOnSessionId() {
        ChatUser user = new ChatUser("user-1", "token-1");

        assertThat(ChatHistoryService.memoryId(HOTEL, user, UUID.randomUUID().toString()))
                .isEqualTo(ChatHistoryService.memoryId(HOTEL, user, null))
                .startsWith(HOTEL + ":user:");
    }
}
