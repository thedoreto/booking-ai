package com.hotel.langchain.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Паметта на разговорите в RAM, най-много maxConversations. Всеки гост има свой разговор (sessionId),
// затова без лимит паметта би растяла без край; най-дълго неползваният разговор се изтрива пръв.
public class BoundedChatMemoryStore implements ChatMemoryStore {

    private final Map<Object, List<ChatMessage>> conversations;

    public BoundedChatMemoryStore(int maxConversations) {
        this.conversations = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, List<ChatMessage>> eldest) {
                return size() > maxConversations;
            }
        };
    }

    @Override
    public synchronized List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> messages = conversations.get(memoryId);
        return messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    @Override
    public synchronized void updateMessages(Object memoryId, List<ChatMessage> messages) {
        conversations.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public synchronized void deleteMessages(Object memoryId) {
        conversations.remove(memoryId);
    }

    synchronized int size() {
        return conversations.size();
    }
}
