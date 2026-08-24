package com.metatron.workforce.interaction;

import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetatronInteractionOrchestratorTest {
    private final ActorRef human = new ActorRef("HUMAN-001", ActorRef.ActorType.HUMAN);
    private final ActorRef worker = new ActorRef("WORKER-001", ActorRef.ActorType.WORKER);

    @Test
    void delegatesCanonicalInteractionAndReturnsAttributableResponse() {
        MetatronInteraction interaction = new MetatronInteraction(
                human, worker, "ORG-001", "CONV-001", "telegram://chat/1/message/2", "audit gateway");

        MetatronInteractionOrchestrator orchestrator = new MetatronInteractionOrchestrator(
                value -> new MetatronInteractionOrchestrator.InteractionResponse(
                        value.conversationId(), "accepted: " + value.text(), value.externalMessageReference()));

        MetatronInteractionOrchestrator.InteractionResponse response = orchestrator.handle(interaction);

        assertEquals("CONV-001", response.conversationId());
        assertEquals("accepted: audit gateway", response.text());
        assertEquals("telegram://chat/1/message/2", response.provenanceReference());
    }

    @Test
    void rejectsBlankInteractionText() {
        assertThrows(IllegalArgumentException.class, () -> new MetatronInteraction(
                human, worker, "ORG-001", "CONV-001", "telegram://x", " "));
    }
}
