package com.metatron.workforce.interaction;

import java.util.Locale;
import java.util.Objects;

/** Channel-neutral control surface for switching one durable Conversation between Chat and Meeting. */
public final class ConversationSurfaceModeService {
    public static final String CHAT_CONTROL = "💬 Chat";
    public static final String MEETING_CONTROL = "🏛 Meeting";

    private final PersistentConversationSurfaceModeStore store;

    public ConversationSurfaceModeService(PersistentConversationSurfaceModeStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public ControlResult handle(String conversationId, String text) {
        Objects.requireNonNull(conversationId, "conversationId");
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals(CHAT_CONTROL.toLowerCase(Locale.ROOT)) || normalized.equals("/chat")) {
            store.set(conversationId, ConversationSurfaceMode.CHAT);
            return new ControlResult(true, ConversationSurfaceMode.CHAT,
                    "💬 CHAT · METATRON\nChat mode enabled. Normal conversation stays in Conversation/Intelligence and does not create a Workforce Objective.");
        }
        if (normalized.equals(MEETING_CONTROL.toLowerCase(Locale.ROOT)) || normalized.equals("/meeting")) {
            store.set(conversationId, ConversationSurfaceMode.MEETING);
            return new ControlResult(true, ConversationSurfaceMode.MEETING,
                    "🏛 MEETING · METATRON\nMeeting mode enabled. Name at least two institutional roles and the topic. Meeting produces a durable follow-up; it does not create a Workforce Objective until you explicitly approve the follow-up.");
        }
        return new ControlResult(false, store.get(conversationId), "");
    }

    public ConversationSurfaceMode mode(String conversationId) {
        return store.get(conversationId);
    }

    public record ControlResult(boolean handled, ConversationSurfaceMode mode, String response) {}
}
