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
                    "🧰 WORK · MEETING\nMeeting room ready. Invite a role, for example: Head of Gateway. Then talk naturally like they are in the room with you. Deliberation, minutes, decisions, and execution happen only when you explicitly ask for them.");
        }
        ConversationSurfaceMode current = store.get(conversationId);
        if (current == ConversationSurfaceMode.WORK && requestsMeetingModule(normalized)) {
            // Natural-language Work intent should enter Meeting and continue processing the SAME utterance.
            // Do not consume the turn with a mode-switch acknowledgement and force the Human to repeat themselves.
            store.set(conversationId, ConversationSurfaceMode.WORK_MEETING);
            return new ControlResult(false, ConversationSurfaceMode.WORK_MEETING, "");
        }
        return new ControlResult(false, current, "");
    }

    static boolean requestsMeetingModule(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        String value = normalized.toLowerCase(Locale.ROOT);
        boolean meetingWord = value.contains("meeting")
                || value.contains("cuoc hop")
                || value.contains("họp")
                || value.contains("hop ")
                || value.startsWith("hop");
        if (!meetingWord) return false;
        return value.startsWith("meeting")
                || value.contains("have a meeting")
                || value.contains("call for me")
                || value.contains("call ")
                || value.contains("meet with")
                || value.contains("meeting with")
                || value.contains("meeting,")
                || value.contains("meeting ")
                || value.contains("muon hop")
                || value.contains("muốn họp")
                || value.contains("goi ")
                || value.contains("gọi ");
    }

    public ConversationSurfaceMode mode(String conversationId) {
        return store.get(conversationId);
    }

    public record ControlResult(boolean handled, ConversationSurfaceMode mode, String response) {}
}
