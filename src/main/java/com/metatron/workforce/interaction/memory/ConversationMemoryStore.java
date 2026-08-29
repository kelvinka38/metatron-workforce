package com.metatron.workforce.interaction.memory;

/**
 * Channel-neutral durable conversational memory boundary.
 * Conversation identity is supplied by the canonical Metatron interaction layer,
 * never inferred from a particular transport implementation.
 */
public interface ConversationMemoryStore {
    String context(String conversationId, int maxTurns, int maxChars);

    default String contextFor(String conversationId, String currentText,
                              int maxRecentTurns, int maxRelevantTurns, int maxChars) {
        return context(conversationId, maxRecentTurns, maxChars);
    }

    void appendTurn(String conversationId, String humanText, String metatronText);
}
