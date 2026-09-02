package com.metatron.workforce.interaction.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramBotGatewayChunkingTest {
    @Test
    void preservesLogicalMessageWhileBoundingEveryTelegramChunk() {
        String section = "Head of Strategy recommendation with disagreement, risk and follow-up.\n";
        String text = section.repeat(180);

        List<String> chunks = TelegramBotGateway.splitMessageText(text);

        assertTrue(chunks.size() > 1);
        assertEquals(text, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk ->
                chunk.codePointCount(0, chunk.length()) <= TelegramBotGateway.SAFE_MESSAGE_CODE_POINTS));
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.isEmpty()));
    }

    @Test
    void doesNotSplitSupplementaryUnicodeCodePointsAtHardBoundary() {
        String emoji = "🧠";
        String text = "a".repeat(TelegramBotGateway.SAFE_MESSAGE_CODE_POINTS - 1)
                + emoji
                + "b".repeat(TelegramBotGateway.SAFE_MESSAGE_CODE_POINTS);

        List<String> chunks = TelegramBotGateway.splitMessageText(text);

        assertEquals(text, String.join("", chunks));
        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            assertTrue(chunk.codePointCount(0, chunk.length()) <= TelegramBotGateway.SAFE_MESSAGE_CODE_POINTS);
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
        }
    }

    @Test
    void leavesOrdinaryShortMessageAsSingleChunk() {
        String text = "Metatron ready";
        assertEquals(List.of(text), TelegramBotGateway.splitMessageText(text));
    }
}
