package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.memory.PersistentConversationMemoryStore;

import java.nio.file.Path;

/** @deprecated Telegram does not own memory. Use PersistentConversationMemoryStore. */
@Deprecated
public final class PersistentTelegramConversationMemory {
    private final PersistentConversationMemoryStore delegate;

    public PersistentTelegramConversationMemory(Path root, ObjectMapper objectMapper) {
        this.delegate = new PersistentConversationMemoryStore(root, objectMapper);
    }

    public String context(String conversationId, int maxTurns, int maxChars) {
        return delegate.context(conversationId, maxTurns, maxChars);
    }

    public void appendTurn(String conversationId, String user, String assistant) {
        delegate.appendTurn(conversationId, user, assistant);
    }
}
