package com.metatron.workforce.interaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSurfaceModeServiceTest {
    @TempDir Path temp;

    @Test
    void persistsChatAndMeetingModeAcrossServiceRecreation() {
        String conversation = "conversation:human:founder";
        ConversationSurfaceModeService first = new ConversationSurfaceModeService(
                new PersistentConversationSurfaceModeStore(temp));

        assertEquals(ConversationSurfaceMode.CHAT, first.mode(conversation));

        var meeting = first.handle(conversation, "🏛 Meeting");
        assertTrue(meeting.handled());
        assertEquals(ConversationSurfaceMode.MEETING, meeting.mode());
        assertTrue(meeting.response().contains("Meeting mode enabled"));

        ConversationSurfaceModeService reloaded = new ConversationSurfaceModeService(
                new PersistentConversationSurfaceModeStore(temp));
        assertEquals(ConversationSurfaceMode.MEETING, reloaded.mode(conversation));

        var chat = reloaded.handle(conversation, "/chat");
        assertTrue(chat.handled());
        assertEquals(ConversationSurfaceMode.CHAT, chat.mode());

        assertEquals(ConversationSurfaceMode.CHAT,
                new ConversationSurfaceModeService(new PersistentConversationSurfaceModeStore(temp)).mode(conversation));
    }

    @Test
    void ordinaryTextDoesNotChangeMode() {
        String conversation = "conversation:human:founder";
        ConversationSurfaceModeService service = new ConversationSurfaceModeService(
                new PersistentConversationSurfaceModeStore(temp));

        assertFalse(service.handle(conversation, "giá vàng hôm nay?").handled());
        assertEquals(ConversationSurfaceMode.CHAT, service.mode(conversation));
    }
}
