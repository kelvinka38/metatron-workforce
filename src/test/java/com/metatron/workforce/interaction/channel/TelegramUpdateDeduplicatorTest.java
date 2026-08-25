package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelegramUpdateDeduplicatorTest {

    @Test
    void acceptsFirstUpdate() {
        var deduplicator = new TelegramUpdateDeduplicator();

        assertTrue(deduplicator.accept(100));
        assertEquals(100, deduplicator.highestAcceptedUpdateId());
    }

    @Test
    void rejectsSameUpdateId() {
        var deduplicator = new TelegramUpdateDeduplicator();

        assertTrue(deduplicator.accept(100));
        assertFalse(deduplicator.accept(100));
        assertEquals(100, deduplicator.highestAcceptedUpdateId());
    }

    @Test
    void rejectsDelayedOlderUpdate() {
        var deduplicator = new TelegramUpdateDeduplicator();

        assertTrue(deduplicator.accept(100));
        assertTrue(deduplicator.accept(101));
        assertFalse(deduplicator.accept(99));
        assertEquals(101, deduplicator.highestAcceptedUpdateId());
    }

    @Test
    void acceptsStrictlyNewerUpdate() {
        var deduplicator = new TelegramUpdateDeduplicator();

        assertTrue(deduplicator.accept(100));
        assertTrue(deduplicator.accept(101));
        assertTrue(deduplicator.accept(102));
        assertEquals(102, deduplicator.highestAcceptedUpdateId());
    }

    @Test
    void rejectsInvalidUpdateId() {
        var deduplicator = new TelegramUpdateDeduplicator();

        assertThrows(
                IllegalArgumentException.class,
                () -> deduplicator.accept(-1)
        );
    }
}
