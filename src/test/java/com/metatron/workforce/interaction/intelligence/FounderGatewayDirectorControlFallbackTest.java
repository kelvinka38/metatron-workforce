package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FounderGatewayDirectorControlFallbackTest {
    @Test
    void exactFounderTelegramCommandMaterializesExecutionIntentWithoutSemanticProvider() {
        AtomicReference<NormalizedRequest> captured = new AtomicReference<>();
        ExecutionObjectiveHandoff handoff = new ExecutionObjectiveHandoff() {
            @Override
            public HandoffReceipt submit(
                    String humanId,
                    String organizationContextId,
                    String caseId,
                    String conversationId,
                    String externalMessageReference,
                    String channel,
                    NormalizedRequest request) {
                captured.set(request);
                return new HandoffReceipt(
                        true,
                        "objective:gateway-director",
                        "worker-head",
                        "queue:gateway-director",
                        "ACCEPTED",
                        "NOT_YET_ADMITTED",
                        "accepted");
            }
        };

        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", new InMemoryIntelligenceCaseStore(), handoff);

        String answer = responder.respond(
                "human-primary",
                "Hey, create for me a workforce, role gateway head",
                "telegram:update:1",
                "telegram",
                "conversation:human:human-primary",
                "organization:metatron",
                "");

        assertTrue(answer.startsWith("METATRON WORK ACCEPTED"));
        assertTrue(answer.contains("objective_id=objective:gateway-director"));
        NormalizedRequest request = captured.get();
        assertEquals(IntelligenceMode.EXECUTION, request.mode());
        assertEquals("ROLE-HEAD-OF-GATEWAY", request.target());
        assertTrue(request.objective().contains("Gateway Director"));
        assertNull(request.semanticProvider());
    }

    @Test
    void sameProviderFreeControlPhraseIsNotAvailableToNonFounderIdentity() {
        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", new InMemoryIntelligenceCaseStore(), ExecutionObjectiveHandoff.unavailable());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                responder.respond(
                        "human-other",
                        "create for me a workforce, role gateway head",
                        "telegram:update:2",
                        "telegram",
                        "conversation:human:human-other",
                        "organization:metatron",
                        ""));

        assertEquals("semantic_provider_required", failure.getMessage());
    }
}
