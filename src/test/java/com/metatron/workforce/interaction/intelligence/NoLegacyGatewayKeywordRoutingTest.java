package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NoLegacyGatewayKeywordRoutingTest {
    @Test
    void gatewayAuditPhraseMustPassThroughFrontierSemanticBoundary() {
        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "http://127.0.0.1:65535", "", new InMemoryIntelligenceCaseStore());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                responder.respond("human-1", "audit gateway", "message-1", "test", "conversation-1", ""));

        assertEquals("semantic_provider_required", failure.getMessage());
    }
}
