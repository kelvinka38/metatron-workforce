package com.metatron.workforce.interaction.intelligence;

/** Persistent Human-selected depth preference scoped to a conversation. */
public interface IntelligenceDepthPreferenceStore {
    IntelligenceDepthContract get(String conversationId);
    void set(String conversationId, IntelligenceDepth depth);
    void clear(String conversationId);
}
