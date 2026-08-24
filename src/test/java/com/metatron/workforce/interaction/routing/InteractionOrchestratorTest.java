package com.metatron.workforce.interaction.routing;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InteractionOrchestratorTest {
    @Test
    void resolvesHumanCommandToWorker() {
        var orchestrator = new InteractionOrchestrator(new IntentParser(),
                new WorkerRouter(Map.of("audit:g4 gateway", "gateway-auditor")));
        assertEquals("gateway-auditor", orchestrator.resolve("audit G4 gateway").workerId());
    }

    @Test
    void refusesUnknownIntent() {
        var orchestrator = new InteractionOrchestrator(new IntentParser(), new WorkerRouter(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.resolve("hello there"));
    }
}
