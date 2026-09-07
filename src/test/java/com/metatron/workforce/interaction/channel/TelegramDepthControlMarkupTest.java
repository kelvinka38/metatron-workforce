package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramDepthControlMarkupTest {
    @Test
    void exposesHumanReadableDepthAndMonitoringControlsWithoutOwningCanonicalState() {
        Map<String, Object> markup = TelegramBotGateway.depthControlReplyMarkup();

        assertEquals(List.of(
                List.of("💬 Chat", "🏛 Meeting"),
                List.of("⚡ Fast", "🧠 Analyze", "🔬 Deep"),
                List.of("🤖 Auto", "🎛 Mode"),
                List.of("📊 Monitor task")), markup.get("keyboard"));
        assertEquals(true, markup.get("resize_keyboard"));
        assertEquals(true, markup.get("is_persistent"));
        assertTrue(markup.containsKey("input_field_placeholder"));
    }
}