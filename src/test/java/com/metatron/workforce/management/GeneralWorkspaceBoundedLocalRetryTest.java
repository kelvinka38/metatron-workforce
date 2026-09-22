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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finishes the original Workforce Runtime v1 requirement: an ordinary DELIVER execution failure
 * (a Git/command/provider/runtime error, never authorization/data/safety-gate/staffing) must
 * recover via a bounded retry of DELIVER alone, in the SAME graph version -- never a REPLAN --
 * so PRODUCE/PREPARE/VERIFY evidence and completion are never touched.
 */
class GeneralWorkspaceBoundedLocalRetryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T20:00:00Z"), ZoneOffset.UTC);

    @Test
    void deliverFailureRetriesInPlaceWhilePriorPhasesStayCompletedAndNoReplanOccurs() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger deliverAttempts = new AtomicInteger();
        List<String> executedSteps = new CopyOnWriteArrayList<>();

        AutonomousExecutionCapability workspace = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return GeneralWorkspaceAutonomousCapability.CAPABILITY; }
            @Override public MutationRecoveryPolicy mutationRecoveryPolicy() {
                return MutationRecoveryPolicy.BOUNDED_RETRY;
            }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                String stepId = request.workSpec().stepId();
                executedSteps.add(stepId);
                if (stepId.equals("deliver")) {
                    int attempt = deliverAttempts.incrementAndGet();
                    if (attempt <= 2) {
                        throw new IllegalStateException("git-commit-author-identity-missing-attempt-" + attempt);
                    }
                    return new CapabilityResult(true, "worker-general", "assignment-deliver",
                            "work-deliver", List.of("evidence:deliver-committed"), "PASS");
                }
                return new CapabilityResult(true, "worker-general", "assignment-" + stepId,
                        "work-" + stepId, List.of("evidence:" + stepId + "-done"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(workspace), coordination, CLOCK,
                "runner-bounded-local-retry", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-bounded-local-retry", "worker-head", "org-metatron",
                "Build, verify, and deliver a change with a transient DELIVER failure",
                "human:founder", "request-admission:bounded-local-retry", "case-bounded-local-retry",
                "conversation-bounded-local-retry", "message-bounded-local-retry", "telegram",
                phasedRequest(), CLOCK.instant());

        runner.runOnce();

        // Final ManagementObjective.COMPLETED for a MUTATING graph additionally requires a wired
        // CompletionGate/Observation closure (AutonomyCoordinationService.requireGovernedCompletion),
        // which is out of scope here -- see AssignmentLifecycleObservabilityTest's identical note. What
        // this test proves is unaffected by that: every phase's own completion, evidence, and the
        // complete absence of any REPLAN/superseded-graph activity around the DELIVER retry.
        AutonomousObjectiveWork work = management.findAutonomousWork("objective-bounded-local-retry").orElseThrow();
        assertEquals(List.of("produce", "prepare", "verify", "deliver"), work.completedStepIds());
        assertTrue(work.evidenceReferences().contains("evidence:deliver-committed"));
        assertEquals(3, deliverAttempts.get(), "DELIVER must actually be retried in place up to the bound");
        assertEquals(1, executedSteps.stream().filter("produce"::equals).count(),
                "PRODUCE must never be re-run because DELIVER failed");
        assertEquals(1, executedSteps.stream().filter("prepare"::equals).count(),
                "PREPARE must never be re-run because DELIVER failed");
        assertEquals(1, executedSteps.stream().filter("verify"::equals).count(),
                "VERIFY must never be re-run because DELIVER failed");
        assertEquals(3, executedSteps.stream().filter("deliver"::equals).count());

        assertEquals(0, management.history("objective-bounded-local-retry").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                        .count(),
                "an ordinary DELIVER execution failure must never trigger REPLAN");
        assertEquals(List.of(DurableWorkGraph.Status.ACTIVE),
                coordination.graphHistory("objective-bounded-local-retry").stream()
                        .map(DurableWorkGraph::status).toList(),
                "there must be exactly one graph version -- no SUPERSEDED replan graph was ever created");

        List<ManagementAutonomyService.ManagementEvent> localRecoveries = management.history(
                        "objective-bounded-local-retry").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                .toList();
        assertEquals(2, localRecoveries.size());
        assertTrue(localRecoveries.stream().allMatch(event -> event.detail().contains("bounded-local-retry")));
    }

    @Test
    void deliverFailureExhaustingBoundedRetryEscalatesWithoutReplanOrPermanentBlock() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger deliverAttempts = new AtomicInteger();

        AutonomousExecutionCapability workspace = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return GeneralWorkspaceAutonomousCapability.CAPABILITY; }
            @Override public MutationRecoveryPolicy mutationRecoveryPolicy() {
                return MutationRecoveryPolicy.BOUNDED_RETRY;
            }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                if (request.workSpec().stepId().equals("deliver")) {
                    deliverAttempts.incrementAndGet();
                    throw new IllegalStateException("git-commit-persistently-unavailable");
                }
                return new CapabilityResult(true, "worker-general", "assignment-" + request.workSpec().stepId(),
                        "work-" + request.workSpec().stepId(),
                        List.of("evidence:" + request.workSpec().stepId() + "-done"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(workspace), coordination, CLOCK,
                "runner-bounded-local-retry-exhausted", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-bounded-local-retry-exhausted", "worker-head", "org-metatron",
                "Build and verify a change whose DELIVER never recovers",
                "human:founder", "request-admission:bounded-local-retry-exhausted", "case-exhausted",
                "conversation-exhausted", "message-exhausted", "telegram",
                phasedRequest(), CLOCK.instant());

        runner.runOnce();

        assertEquals(3, deliverAttempts.get(), "the bound must stop DELIVER after exactly 3 attempts");
        assertEquals(ManagementObjective.Status.ESCALATED,
                management.get("objective-bounded-local-retry-exhausted").status(),
                "exhausted bounded local retry must escalate for Human review, not stay BLOCKED forever "
                        + "as if it were a genuine authorization/data/safety/staffing blocker");
        assertEquals(0, management.history("objective-bounded-local-retry-exhausted").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                        .count(),
                "exhausting the bound must never fall back to REPLAN either");
        assertEquals(List.of(DurableWorkGraph.Status.ACTIVE),
                coordination.graphHistory("objective-bounded-local-retry-exhausted").stream()
                        .map(DurableWorkGraph::status).toList(),
                "there must still be exactly one graph version -- no SUPERSEDED replan graph");
        assertTrue(management.history("objective-bounded-local-retry-exhausted").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.ESCALATED)
                .anyMatch(event -> event.detail().contains("bounded-local-retry-exhausted")));
    }

    private static NormalizedRequest phasedRequest() {
        String capability = GeneralWorkspaceAutonomousCapability.CAPABILITY;
        ExecutionWorkSpec produce = new ExecutionWorkSpec("produce", "Produce source", "target",
                capability, List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("source exists"), List.of("produce evidence"));
        ExecutionWorkSpec prepare = new ExecutionWorkSpec("prepare", "Prepare project", "target",
                capability, List.of("produce"), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("manifest exists"), List.of("prepare evidence"));
        ExecutionWorkSpec verify = new ExecutionWorkSpec("verify", "Verify build and tests", "target",
                capability, List.of("prepare"), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"), List.of("verify evidence"));
        ExecutionWorkSpec deliver = new ExecutionWorkSpec("deliver", "Deliver committed change", "target",
                capability, List.of("verify"), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("commit exists"), List.of("deliver evidence"));
        return new NormalizedRequest(
                "Ship a change end to end", "target", List.of("execute autonomously"), IntelligenceDepth.ANALYZE,
                "evidence-backed durable work product", List.of(), List.of(), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(produce, prepare, verify, deliver), false, null, LlmProvider.OPENAI, "");
    }
}
