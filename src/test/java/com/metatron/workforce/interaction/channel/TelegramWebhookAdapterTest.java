package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelegramWebhookAdapterTest {
    @Test
    void acceptsOnlyValidSecret() {
        var adapter = new TelegramWebhookAdapter("secret");
        var msg = adapter.receive("secret", "human-1", "audit G4 gateway");
        assertEquals("telegram", msg.channel());
        assertEquals("audit G4 gateway", msg.text());
    }

    @Test
    void rejectsInvalidSecret() {
        var adapter = new TelegramWebhookAdapter("secret");
        assertThrows(SecurityException.class,
                () -> adapter.receive("wrong", "human-1", "audit G4 gateway"));
    }

    @Test
    void rejectsExactEcho() {
        assertThrows(IllegalStateException.class,
                () -> TelegramWebhookController.validateAnswer("Hôm nay thứ mấy?", "Hôm nay thứ mấy?"));
    }

    @Test
    void rejectsLegacyWorkforceEcho() {
        assertThrows(IllegalStateException.class,
                () -> TelegramWebhookController.validateAnswer("Hôm nay thứ mấy?", "Workforce received: Hôm nay thứ mấy?"));
    }

    @Test
    void acceptsActualAiAnswer() {
        assertEquals("Hôm nay là thứ Hai.",
                TelegramWebhookController.validateAnswer("Hôm nay thứ mấy?", "Hôm nay là thứ Hai."));
    }
}
