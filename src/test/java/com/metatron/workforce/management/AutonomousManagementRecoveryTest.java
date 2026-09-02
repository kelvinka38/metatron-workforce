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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousManagementRecoveryTest {
    @Test
    void routineReadOnlyFailureIsRecoveredWithoutHumanOperationAndWithinBoundedAttempts() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T13:00:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger executions = new AtomicInteger();

        AutonomousExecutionCapability flaky = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                int attempt = executions.incrementAndGet();
                if (attempt <= 2) throw new IllegalStateException("provider-timeout-attempt-" + attempt);
                return new CapabilityResult(true, "worker-recovery", "assignment-recovery",
                        "work-recovery", List.of("evidence:recovered-on-attempt-3"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(flaky), coordination, clock,
                "runner-recovery", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-recovery", "worker-head", "org-metatron", "Recover a routine read failure",
                "human:founder", "request-admission:recovery", "case-recovery", "conversation-recovery",
                "message-recovery", "telegram", request(), clock.instant());

        runner.runOnce();

        assertEquals(3, executions.get(), "retry policy must be bounded and stop after success");
        assertEquals(ManagementObjective.Status.COMPLETED, management.get("objective-recovery").status());
        assertTrue(management.get("objective-recovery").evidenceRefs().contains("evidence:recovered-on-attempt-3"));

        long blocked = management.history("objective-recovery").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.BLOCKED)
                .count();
        long recovered = management.history("objective-recovery").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                .count();
        assertEquals(2, blocked);
        assertEquals(2, recovered);
        assertTrue(management.history("objective-recovery").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                .allMatch(event -> event.detail().contains("bounded-read-only-retry")));

        List<DurableDispatch> dispatches = coordination.dispatches().stream()
                .filter(dispatch -> dispatch.objectiveId().equals("objective-recovery"))
                .toList();
        assertEquals(3, dispatches.size());
        assertEquals(List.of(DurableDispatch.Status.FAILED, DurableDispatch.Status.FAILED, DurableDispatch.Status.SUCCEEDED),
                dispatches.stream().map(DurableDispatch::status).toList());
        assertTrue(coordination.deadLetters().isEmpty());
    }

    @Test
    void exhaustedRoutineRecoveryTriggersOneAutonomousReplanAndCompletesOnNewGraph() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T13:05:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger executions = new AtomicInteger();

        AutonomousExecutionCapability recoveredAfterReplan = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                int attempt = executions.incrementAndGet();
                if (attempt <= 3) throw new IllegalStateException("provider-remained-unavailable-" + attempt);
                return new CapabilityResult(true, "worker-replanned", "assignment-replanned",
                        "work-replanned", List.of("evidence:replanned-outcome"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(recoveredAfterReplan), coordination, clock,
                "runner-replan", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-replan", "worker-head", "org-metatron", "Recover through a managed replan",
                "human:founder", "request-admission:replan", "case-replan", "conversation-replan",
                "message-replan", "telegram", request(), clock.instant());

        runner.runOnce();
        assertEquals(ManagementObjective.Status.REPLANNING, management.get("objective-replan").status());
        assertEquals(1, management.history("objective-replan").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                .count());

        runner.runOnce();

        assertEquals(4, executions.get());
        assertEquals(ManagementObjective.Status.COMPLETED, management.get("objective-replan").status());
        assertEquals(List.of(DurableWorkGraph.Status.SUPERSEDED, DurableWorkGraph.Status.COMPLETED),
                coordination.graphHistory("objective-replan").stream().map(DurableWorkGraph::status).toList());
        assertTrue(management.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveOutcomeReportReady")
                        && message.payload().contains("evidence:replanned-outcome")));
    }

    @Test
    void repeatedFailureAfterBoundedReplanEscalatesInsteadOfLoopingForever() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T13:07:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger executions = new AtomicInteger();

        AutonomousExecutionCapability unavailable = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                executions.incrementAndGet();
                throw new IllegalStateException("provider-persistently-unavailable");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(unavailable), coordination, clock,
                "runner-bounded-replan", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-bounded-replan", "worker-head", "org-metatron", "Bound impossible recovery",
                "human:founder", "request-admission:bounded-replan", "case-bounded-replan",
                "conversation-bounded-replan", "message-bounded-replan", "telegram",
                request(), clock.instant());

        runner.runOnce();
        runner.runOnce();

        assertEquals(6, executions.get());
        assertEquals(ManagementObjective.Status.ESCALATED,
                management.get("objective-bounded-replan").status());
        assertEquals(1, management.history("objective-bounded-replan").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                .count());
        assertTrue(management.history("objective-bounded-replan").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.ESCALATED)
                .anyMatch(event -> event.detail().contains("bounded-autonomous-recovery-exhausted")));
    }

    @Test
    void authorizationFailureIsNeverAutomaticallyRetried() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T13:10:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger executions = new AtomicInteger();

        AutonomousExecutionCapability denied = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                executions.incrementAndGet();
                throw new SecurityException("authorization-revoked");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(denied), coordination, clock,
                "runner-denied", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-denied", "worker-head", "org-metatron", "Denied read",
                "human:founder", "request-admission:denied", "case-denied", "conversation-denied",
                "message-denied", "telegram", request(), clock.instant());

        runner.runOnce();

        assertEquals(1, executions.get());
        assertEquals(ManagementObjective.Status.BLOCKED, management.get("objective-denied").status());
        assertTrue(management.findAutonomousWork("objective-denied").orElseThrow().blocker()
                .contains("authorization-failure"));
        assertEquals(0, management.history("objective-denied").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                .count());
    }

    private static NormalizedRequest request() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-1", "Perform recoverable read", "target", "test.recovery.read", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("read completes"), List.of("execution evidence"));
        return new NormalizedRequest(
                "Perform recoverable read", "target", List.of("read-only"), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of("do not mutate"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
