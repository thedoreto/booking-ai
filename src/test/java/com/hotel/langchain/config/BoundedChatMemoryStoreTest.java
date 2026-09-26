package com.hotel.langchain.config;

import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedChatMemoryStoreTest {

    @Test
    void keepsConversationsApartAndDropsTheLeastRecentlyUsed() {
        BoundedChatMemoryStore store = new BoundedChatMemoryStore(2);
        store.updateMessages("a", List.of(UserMessage.from("от А")));
        store.updateMessages("b", List.of(UserMessage.from("от Б")));
        store.getMessages("a"); // А е ползван скоро – изтрива се Б
        store.updateMessages("c", List.of(UserMessage.from("от В")));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.getMessages("a")).containsExactly(UserMessage.from("от А"));
        assertThat(store.getMessages("b")).isEmpty();
        assertThat(store.getMessages("c")).containsExactly(UserMessage.from("от В"));
    }
}
