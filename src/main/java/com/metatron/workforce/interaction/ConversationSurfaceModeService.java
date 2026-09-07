package com.metatron.workforce.interaction;

import java.util.Locale;
import java.util.Objects;

/** Channel-neutral product surface: top-level Chat or Work; Meeting is a Work module. */
public final class ConversationSurfaceModeService {
    public static final String CHAT_CONTROL = "💬 Chat";
    public static final String WORK_CONTROL = "🧰 Work";
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
        if (normalized.equals(WORK_CONTROL.toLowerCase(Locale.ROOT)) || normalized.equals("/work")) {
            store.set(conversationId, ConversationSurfaceMode.WORK);
            return new ControlResult(true, ConversationSurfaceMode.WORK,
                    "🧰 WORK · METATRON\nWork mode enabled. Choose a Work module: Meeting or Monitor. Work is the management/execution surface; Chat remains separate.");
        }
        if (normalized.equals(MEETING_CONTROL.toLowerCase(Locale.ROOT)) || normalized.equals("/meeting")) {
            ConversationSurfaceMode current = store.get(conversationId);
            if (current == ConversationSurfaceMode.CHAT) {
                return new ControlResult(true, ConversationSurfaceMode.CHAT,
                        "🧰 WORK · METATRON\nMeeting belongs inside Work. Open 🧰 Work first.");
            }
            store.set(conversationId, ConversationSurfaceMode.WORK_MEETING);
            return new ControlResult(true, ConversationSurfaceMode.WORK_MEETING,
                    "🧰 WORK · MEETING\nMeeting module ready. Describe the topic and the institutional roles naturally. Meeting creates coordination output and a durable follow-up; execution still requires explicit approval.");
        }
        return new ControlResult(false, store.get(conversationId), "");
    }

    public ConversationSurfaceMode mode(String conversationId) {
        return store.get(conversationId);
    }

    public record ControlResult(boolean handled, ConversationSurfaceMode mode, String response) {}
}
