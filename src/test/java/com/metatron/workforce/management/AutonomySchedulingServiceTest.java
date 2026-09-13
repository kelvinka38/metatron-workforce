package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomySchedulingServiceTest {
    @TempDir Path temp;

    @Test
    void finiteCapacityBoundsParallelReadySetAndDecisionSurvivesReplacement() {
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        WorkforceCoreService core = new WorkforceCoreService();
        addWorker(core, "worker-a", 1.0);
        addWorker(core, "worker-b", 1.0);
        AutonomySafetyService safety = safety(now);
        Path state = temp.resolve("scheduler.json");
        AutonomySchedulingService scheduler = new AutonomySchedulingService(
                core, safety, new FileAutonomySchedulingStateStore(state), 4);
        AutonomousExecutionCapability capability = capability();

        AutonomySchedulingDecision decision = scheduler.decide(
                "objective-capacity", 1,
                List.of(node("step-a", ExecutionWorkSpec.Consequence.READ_ONLY),
                        node("step-b", ExecutionWorkSpec.Consequence.READ_ONLY),
                        node("step-c", ExecutionWorkSpec.Consequence.READ_ONLY)),
                Map.of(capability.capabilityRef(), capability), now);

        assertEquals(2, decision.selectedStepIds().size());
        assertEquals(2, decision.projectedWorkerByStep().size());
        assertEquals("finite-capacity-unavailable", decision.deferredReasons().get("step-c"));

        AutonomySchedulingService replacement = new AutonomySchedulingService(
                core, safety, new FileAutonomySchedulingStateStore(state), 4);
        assertEquals(1, replacement.decisionsForObjective("objective-capacity").size());
        assertEquals(decision.decisionId(), replacement.decisionsForObjective("objective-capacity").getFirst().decisionId());
    }

    @Test
    void budgetRiskAndStructuralCeilingsAreAppliedBeforeDispatch() {
        Instant now = Instant.parse("2026-08-31T12:10:00Z");
        WorkforceCoreService core = new WorkforceCoreService();
        addWorker(core, "worker-a", 4.0);
        AutonomySafetyService safety = safety(now);
        safety.configureEnvelope("objective-budget", 1.1, 10, now.plusSeconds(600),
                AutonomySafetyState.RiskLevel.HIGH, "authority:test", now);
        AutonomySchedulingService scheduler = new AutonomySchedulingService(
                core, safety, new InMemoryAutonomySchedulingStateStore(), 4);
        AutonomousExecutionCapability capability = capability();

        AutonomySchedulingDecision budget = scheduler.decide(
                "objective-budget", 1,
                List.of(node("step-a", ExecutionWorkSpec.Consequence.READ_ONLY),
                        node("step-b", ExecutionWorkSpec.Consequence.READ_ONLY)),
                Map.of(capability.capabilityRef(), capability), now.plusSeconds(1));
        assertEquals(List.of("step-a"), budget.selectedStepIds());
        assertEquals("budget-threshold-exceeded", budget.deferredReasons().get("step-b"));

        safety.configureEnvelope("objective-risk", 20.0, 10, now.plusSeconds(600),
                AutonomySafetyState.RiskLevel.LOW, "authority:test", now);
        AutonomySchedulingDecision risk = scheduler.decide(
                "objective-risk", 1,
                List.of(node("mutate", ExecutionWorkSpec.Consequence.MUTATING)),
                Map.of(capability.capabilityRef(), capability), now.plusSeconds(1));
        assertTrue(risk.selectedStepIds().isEmpty());
        assertEquals("risk-threshold-exceeded:HIGH", risk.deferredReasons().get("mutate"));
    }

    @Test
    void selectedObjectiveOwnerIsPreferredAsActualPerformerWhenEligible() {
        Instant now = Instant.parse("2026-08-31T12:15:00Z");
        WorkforceCoreService core = new WorkforceCoreService();
        addWorker(core, "worker-a", 1.0);
        addWorker(core, "worker-b", 1.0);
        AutonomySchedulingService scheduler = new AutonomySchedulingService(
                core, safety(now), new InMemoryAutonomySchedulingStateStore(), 4);
        AutonomousExecutionCapability capability = capability();

        AutonomySchedulingDecision decision = scheduler.decide(
                "objective-owner", 1,
                List.of(node("step-owner", ExecutionWorkSpec.Consequence.READ_ONLY)),
                Map.of(capability.capabilityRef(), capability), "worker-b", now);

        assertEquals("worker-b", decision.projectedWorkerByStep().get("step-owner"));
    }

    @Test
    void absentCapacityAllowsOnlyOneStaffingBootstrapPerCapability() {
        Instant now = Instant.parse("2026-08-31T12:20:00Z");
        WorkforceCoreService core = new WorkforceCoreService();
        AutonomySafetyService safety = safety(now);
        AutonomySchedulingService scheduler = new AutonomySchedulingService(
                core, safety, new InMemoryAutonomySchedulingStateStore(), 4);
        AutonomousExecutionCapability capability = capability();

        AutonomySchedulingDecision decision = scheduler.decide(
                "objective-staffing", 1,
                List.of(node("step-a", ExecutionWorkSpec.Consequence.READ_ONLY),
                        node("step-b", ExecutionWorkSpec.Consequence.READ_ONLY)),
                Map.of(capability.capabilityRef(), capability), now);

        assertEquals(List.of("step-a"), decision.selectedStepIds());
        assertEquals(List.of("step-a"), decision.staffingBootstrapStepIds());
        assertEquals("finite-capacity-unavailable", decision.deferredReasons().get("step-b"));
    }

    private static AutonomySafetyService safety(Instant now) {
        return new AutonomySafetyService(new InMemoryAutonomySafetyStateStore(),
                Clock.fixed(now, ZoneOffset.UTC), 100.0, 32, Duration.ofHours(1),
                AutonomySafetyState.RiskLevel.HIGH);
    }

    private static void addWorker(WorkforceCoreService core, String workerId, double capacity) {
        String participant = "participant:" + workerId;
        core.recognizeParticipant(participant, WorkforceCoreService.ParticipantType.AI, "test-provenance");
        core.admitWorker(workerId, participant);
        core.participate("participation:" + workerId, workerId, "org:test", "position:test", "role:test");
        core.attestCapability(workerId, "repository.audit.read", 1.0, "evidence:test");
        core.setAvailability(workerId, true, capacity);
    }

    private static DurableWorkGraph.Node node(String stepId, ExecutionWorkSpec.Consequence consequence) {
        ExecutionWorkSpec spec = new ExecutionWorkSpec(stepId, "work " + stepId, "kelvinka38/metatron-workforce",
                "repository.audit.read", List.of(), consequence,
                List.of("criterion"), List.of("evidence"));
        return new DurableWorkGraph.Node(spec, DurableWorkGraph.NodeStatus.PENDING,
                0, "", List.of(), "", Instant.parse("2026-08-31T12:00:00Z"));
    }

    private static AutonomousExecutionCapability capability() {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "repository.audit.read"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, "worker-a", "assignment:test", "work:test", List.of(), "ok");
            }
        };
    }
}
