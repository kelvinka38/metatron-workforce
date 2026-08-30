package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramDepthControlMarkupTest {
    @Test
    void exposesCanonicalDepthCommandsWithoutOwningDepthState() {
        Map<String, Object> markup = TelegramBotGateway.depthControlReplyMarkup();

        assertEquals(List.of(
                List.of("/fast", "/analyze", "/deep"),
                List.of("/auto", "/mode")), markup.get("keyboard"));
        assertEquals(true, markup.get("resize_keyboard"));
        assertEquals(true, markup.get("is_persistent"));
        assertTrue(markup.containsKey("input_field_placeholder"));
    }
}
