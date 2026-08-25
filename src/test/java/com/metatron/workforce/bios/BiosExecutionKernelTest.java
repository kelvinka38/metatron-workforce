package com.metatron.workforce.bios;

import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.MetatronInteractionOrchestrator;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BiosExecutionKernelTest {
    private final BiosExecutionKernel bios = new BiosExecutionKernel();
    private final ActorRef human = new ActorRef("HUMAN-001", ActorRef.ActorType.HUMAN);
    private final ActorRef worker = new ActorRef("WORKER-001", ActorRef.ActorType.WORKER);

    @Test
    void classifiesDiscussion() {
        assertEquals(IntelligenceMode.DISCUSSION, bios.classify("hôm nay thời tiết thế nào?"));
    }

    @Test
    void classifiesReasoning() {
        assertEquals(IntelligenceMode.REASONING, bios.classify("audit g4 gateway"));
    }

    @Test
    void classifiesDecision() {
        assertEquals(IntelligenceMode.DECISION, bios.classify("should we approve this?"));
    }

    @Test
    void classifiesExecution() {
        assertEquals(IntelligenceMode.EXECUTION, bios.classify("deploy this to production"));
    }

    @Test
    void executionRequiresExplicitAuthorization() {
        MetatronInteraction interaction = interaction("deploy this to production");
        assertThrows(IllegalStateException.class, () -> bios.execute(interaction, value -> response(value, "deployed")));
    }

    @Test
    void authorizedExecutionReachesDownstreamAndVerifiesProvenance() {
        MetatronInteraction interaction = interaction("I authorize: deploy this to production");
        MetatronInteractionOrchestrator.InteractionResponse response = bios.execute(
                interaction,
                value -> response(value, "deployment accepted"));

        assertEquals("deployment accepted", response.text());
        assertEquals("telegram://message/1", response.provenanceReference());
    }

    @Test
    void decisionRequiresProvenance() {
        MetatronInteraction interaction = interaction("should we approve this?");
        assertThrows(IllegalStateException.class, () -> bios.execute(
                interaction,
                value -> new MetatronInteractionOrchestrator.InteractionResponse(
                        value.conversationId(), "decision", "")));
    }

    @Test
    void responseEchoIsRejected() {
        MetatronInteraction interaction = interaction("hello workforce");
        assertThrows(IllegalStateException.class, () -> bios.execute(
                interaction,
                value -> response(value, "hello workforce")));
    }

    private MetatronInteraction interaction(String text) {
        return new MetatronInteraction(
                human,
                worker,
                "ORG-001",
                "CONV-001",
                "telegram://message/1",
                text);
    }

    private static MetatronInteractionOrchestrator.InteractionResponse response(
            MetatronInteraction interaction,
            String text) {
        return new MetatronInteractionOrchestrator.InteractionResponse(
                interaction.conversationId(), text, interaction.externalMessageReference());
    }
}
