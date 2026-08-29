package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramCurrentTimeResponseTest {
    @Test
    void naturalLanguageTimeQuestionRequiresFrontierSemanticInterpretation() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> responder.respond("telegram-human", "Hôm nay là thứ mấy ?", "update:time-test"));

        assertEquals("semantic_provider_required", failure.getMessage());
    }
}
