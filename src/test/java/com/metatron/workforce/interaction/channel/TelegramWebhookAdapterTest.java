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
}
