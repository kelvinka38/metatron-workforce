package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementControlLifecycleTest {
    @Test
    void pauseResumeAndCancelAreCanonicalObjectiveTransitions() {
        Instant at = Instant.parse("2026-08-31T05:00:00Z");
        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptHumanObjective("objective-control", "worker-head", "org", "control test",
                "human:founder", "admission", "case-control", "conversation-control",
                "message-control", "telegram", request(), at);

        management.pauseObjective("objective-control", "worker-head", "operator pause", at.plusSeconds(1));
        assertEquals(ManagementObjective.Status.PAUSED, management.get("objective-control").status());
        assertEquals(AutonomousObjectiveWork.Status.BLOCKED,
                management.findAutonomousWork("objective-control").orElseThrow().status());

        management.resumeObjective("objective-control", "worker-head", "operator resume", at.plusSeconds(2));
        assertEquals(ManagementObjective.Status.ACCEPTED, management.get("objective-control").status());
        assertEquals(AutonomousObjectiveWork.Status.PENDING_PLANNING,
                management.findAutonomousWork("objective-control").orElseThrow().status());

        management.cancelObjective("objective-control", "worker-head", "founder cancellation", at.plusSeconds(3));
        assertEquals(ManagementObjective.Status.CANCELLED, management.get("objective-control").status());
        assertEquals(AutonomousObjectiveWork.Status.CANCELLED,
                management.findAutonomousWork("objective-control").orElseThrow().status());
        assertTrue(management.get("objective-control").terminal());
        assertTrue(management.outbox().stream().anyMatch(m -> m.messageType().equals("ObjectiveCancelled")));
    }

    @Test
    void replanCreatesFreshManagementPlanAndSupersedesOldGraphVersion() {
        Instant at = Instant.parse("2026-08-31T05:00:00Z");
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        management.acceptHumanObjective("objective-replan", "worker-head", "org", "replan test",
                "human:founder", "admission", "case-replan", "conversation-replan",
                "message-replan", "api", request(), at);
        ManagementLease lease = management.acquireManagementLease("objective-replan", "runner",
                Duration.ofMinutes(5), at).orElseThrow();

        management.beginPlanning("objective-replan", "runner", lease.token(), at.plusSeconds(1));
        List<ExecutionWorkSpec> plan1 = List.of(step("step-a", "path A"));
        management.recordPlan("objective-replan", "runner", lease.token(), plan1, at.plusSeconds(2));
        DurableWorkGraph graph1 = coordination.ensureGraph("objective-replan", plan1, at.plusSeconds(2));
        assertEquals(1, graph1.graphVersion());

        management.requestReplan("objective-replan", "worker-head", "dependency changed", at.plusSeconds(3));
        assertEquals(ManagementObjective.Status.REPLANNING, management.get("objective-replan").status());
        assertEquals(plan1, management.findAutonomousWork("objective-replan").orElseThrow().plannedWork());

        management.beginPlanning("objective-replan", "runner", lease.token(), at.plusSeconds(4));
        List<ExecutionWorkSpec> plan2 = List.of(step("step-b", "path B"));
        management.recordPlan("objective-replan", "runner", lease.token(), plan2, at.plusSeconds(5));
        DurableWorkGraph graph2 = coordination.ensureGraph("objective-replan", plan2, at.plusSeconds(5));

        assertEquals(2, graph2.graphVersion());
        assertEquals(DurableWorkGraph.Status.ACTIVE, graph2.status());
        assertEquals(2, coordination.graphHistory("objective-replan").size());
        assertEquals(DurableWorkGraph.Status.SUPERSEDED,
                coordination.graphHistory("objective-replan").getFirst().status());
        assertTrue(management.history("objective-replan").stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED));
    }

    private static ExecutionWorkSpec step(String id, String objective) {
        return new ExecutionWorkSpec(id, objective, "target", "test.read", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("criterion"), List.of("evidence ref"));
    }

    private static NormalizedRequest request() {
        return new NormalizedRequest("objective", "target", List.of(), IntelligenceDepth.ANALYZE,
                "result", List.of(), List.of(), "current", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(), false, null, LlmProvider.OPENAI, "");
    }
}
