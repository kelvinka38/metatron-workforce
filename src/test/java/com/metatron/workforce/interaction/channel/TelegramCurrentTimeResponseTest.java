package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramCurrentTimeResponseTest {
    @Test
    void timeQuestionDoesNotRequireAnLlmProvider() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper());

        String answer = responder.respond("telegram-human", "Hôm nay là thứ mấy ?", "update:time-test");

        assertTrue(answer.startsWith("Hôm nay là "));
        assertTrue(answer.contains("giờ Việt Nam"));
    }
}
