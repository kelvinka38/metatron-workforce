package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramExecutionPathTest {
    @Test
    void gatewayAuditNaturalLanguageMustUseFrontierSemanticCapabilitySelection() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "http://127.0.0.1:65535/g4/health", "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> responder.respond("telegram-human", "audit g4 gateway", "update:42"));

        assertEquals("semantic_provider_required", failure.getMessage());
    }

    @Test
    void naturalLanguageIntentIsNotGuessedWhenNoFrontierSemanticProviderExists() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper());

        for (String input : new String[]{"fix it and deploy", "I authorize deployment", "should we proceed"}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> responder.respond("telegram-human", input, "update:no-semantic-provider"));
            assertEquals("semantic_provider_required", failure.getMessage());
        }
    }
}
