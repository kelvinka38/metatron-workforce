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
    void exhaustedReadOnlyRetryEscalatesWithoutReplanAndPreservesPriorCompletedStepEvidence() {
        // Finishes the Runtime v1 contract: REPLAN is for a genuinely invalid/impossible plan, not
        // an ordinary READ_ONLY provider/network/timeout/runtime failure. No trustworthy automatic
        // plan-invalid classifier exists, so exhausting a bounded READ_ONLY retry must never fall
        // back to REPLAN -- it escalates for Human review instead, leaving the graph, and any
        // already-completed step's evidence, completely untouched.
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T13:05:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger flakyAttempts = new AtomicInteger();

        AutonomousExecutionCapability stepOk = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read.ok"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, "worker-ok", "assignment-ok",
                        "work-ok", List.of("evidence:step-ok-done"), "PASS");
            }
        };
        AutonomousExecutionCapability stepFlaky = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read.flaky"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                int attempt = flakyAttempts.incrementAndGet();
                throw new IllegalStateException("provider-remained-unavailable-" + attempt);
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(stepOk, stepFlaky), coordination, clock,
                "runner-readonly-exhausted", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-readonly-exhausted", "worker-head", "org-metatron", "Bound an impossible read",
                "human:founder", "request-admission:readonly-exhausted", "case-readonly-exhausted",
                "conversation-readonly-exhausted", "message-readonly-exhausted", "telegram",
                twoStepRequest(), clock.instant());

        runner.runOnce();

        assertEquals(3, flakyAttempts.get(), "the flaky step must be retried exactly to its bound");
        assertEquals(ManagementObjective.Status.ESCALATED,
                management.get("objective-readonly-exhausted").status(),
                "exhausted bounded read-only retry must escalate for Human review, never fall back to REPLAN");
        assertEquals(0, management.history("objective-readonly-exhausted").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                        .count(),
                "an ordinary READ_ONLY provider/runtime failure must never trigger REPLAN");
        assertEquals(List.of(DurableWorkGraph.Status.ACTIVE),
                coordination.graphHistory("objective-readonly-exhausted").stream()
                        .map(DurableWorkGraph::status).toList(),
                "the graph version must remain unchanged -- no SUPERSEDED replan graph was ever created");

        AutonomousObjectiveWork work = management.findAutonomousWork("objective-readonly-exhausted").orElseThrow();
        assertTrue(work.completedStepIds().contains("step-ok"),
                "a previously completed step must remain completed after a later step's retry exhausts");
        assertTrue(work.evidenceReferences().contains("evidence:step-ok-done"),
                "a previously completed step's evidence must remain intact");
        assertTrue(management.history("objective-readonly-exhausted").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.ESCALATED)
                .anyMatch(event -> event.detail().contains("bounded-read-only-retry-exhausted")));
    }

    @Test
    void explicitManualReplanStillCompletesIndependentlyOfAutomaticEscalation() {
        // Automatic REPLAN is gone for ordinary execution failures, but the explicit/manual replan
        // path (an operator or administrative action deliberately calling
        // ManagementAutonomyService.requestReplan()) must still work on its own, completely
        // independent of the runner's automatic classification.
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T13:07:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger flakyAttempts = new AtomicInteger();

        AutonomousExecutionCapability stepOk = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read.ok"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, "worker-ok", "assignment-ok",
                        "work-ok", List.of("evidence:step-ok-done"), "PASS");
            }
        };
        AutonomousExecutionCapability stepFlaky = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.recovery.read.flaky"; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                int attempt = flakyAttempts.incrementAndGet();
                if (attempt <= 3) throw new IllegalStateException("provider-remained-unavailable-" + attempt);
                return new CapabilityResult(true, "worker-replanned", "assignment-replanned",
                        "work-replanned", List.of("evidence:replanned-outcome"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(stepOk, stepFlaky), coordination, clock,
                "runner-explicit-replan", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-explicit-replan", "worker-head", "org-metatron",
                "Recover through an explicit manual replan",
                "human:founder", "request-admission:explicit-replan", "case-explicit-replan",
                "conversation-explicit-replan", "message-explicit-replan", "telegram",
                twoStepRequest(), clock.instant());

        runner.runOnce();

        assertEquals(3, flakyAttempts.get());
        assertEquals(ManagementObjective.Status.ESCALATED, management.get("objective-explicit-replan").status());
        assertEquals(0, management.history("objective-explicit-replan").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                .count());

        String owner = management.get("objective-explicit-replan").ownerWorkerId();
        management.requestReplan("objective-explicit-replan", owner,
                "manual-operator-recovery-decision", clock.instant());
        assertEquals(ManagementObjective.Status.REPLANNING, management.get("objective-explicit-replan").status());
        assertEquals(1, management.history("objective-explicit-replan").stream()
                .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED)
                .count());

        runner.runOnce();

        assertEquals(4, flakyAttempts.get(), "the explicit replan must give the failed step a fresh attempt budget");
        assertEquals(ManagementObjective.Status.COMPLETED, management.get("objective-explicit-replan").status());
        assertEquals(List.of(DurableWorkGraph.Status.SUPERSEDED, DurableWorkGraph.Status.COMPLETED),
                coordination.graphHistory("objective-explicit-replan").stream().map(DurableWorkGraph::status).toList());
        assertTrue(management.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveOutcomeReportReady")
                        && message.payload().contains("evidence:replanned-outcome")));
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

    @Test
    void invalidActionSelectionFromASingleCognitiveCompletionGetsABoundedFreshRetryInsteadOfImmediateEscalation() {
        // Regression test for a real production failure (objective:intelligence-case:case-6419a010):
        // CognitiveWorkerRuntime throws SecurityException("brain-selected-action-outside-catalog:...")
        // whenever a single cognitive completion selects an actionRef its own catalog does not contain
        // -- including a hallucinated/invalid name that never existed anywhere, not just a genuine
        // privilege-escalation attempt. That SecurityException used to be classified identically to a
        // real authorization/governance denial ("authorization-failure:"), which is never bounded-
        // retried, so the Objective was permanently BLOCKED after exactly one dispatch attempt even
        // though a fresh, independent cognitive completion could plausibly select a valid action next
        // time. This proves the failure is now treated as an ordinary retryable failure instead.
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T22:40:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger executions = new AtomicInteger();

        AutonomousExecutionCapability hallucinatesOnce = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return GeneralWorkspaceAutonomousCapability.CAPABILITY; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                if (executions.incrementAndGet() == 1) {
                    throw new SecurityException("brain-selected-action-outside-catalog:verify-workspace-integrity");
                }
                return new CapabilityResult(true, "worker-recovery", "assignment-recovery",
                        "work-recovery", List.of("evidence:recovered-on-attempt-2"), "PASS");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(hallucinatesOnce), coordination, clock,
                "runner-invalid-action-selection", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-invalid-action-selection", "worker-head", "org-metatron",
                "Verify a workspace after a hallucinated action selection",
                "human:founder", "request-admission:invalid-action-selection", "case-invalid-action-selection",
                "conversation-invalid-action-selection", "message-invalid-action-selection", "telegram",
                generalWorkspaceRequest(), clock.instant());

        runner.runOnce();

        assertEquals(2, executions.get(),
                "an invalid action selection from one cognitive completion must still get a fresh bounded "
                        + "retry attempt, not an immediate zero-retry escalation");
        // Final ManagementObjective.COMPLETED for a MUTATING graph additionally requires a wired
        // CompletionGate/Observation closure (AutonomyCoordinationService.requireGovernedCompletion),
        // out of scope here -- see GeneralWorkspaceBoundedLocalRetryTest's identical note. What this
        // test proves is unaffected by that: the step itself recovered via an ordinary bounded local
        // retry instead of an immediate zero-retry authorization-style escalation.
        AutonomousObjectiveWork work = management.findAutonomousWork("objective-invalid-action-selection").orElseThrow();
        assertEquals(List.of("step-1"), work.completedStepIds());
        assertTrue(work.evidenceReferences().contains("evidence:recovered-on-attempt-2"));
        assertEquals(1, management.history("objective-invalid-action-selection").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                        .count(),
                "the invalid-action-selection failure must be recorded as an ordinary local recovery, "
                        + "not skipped as an authorization/governance denial");
        assertTrue(management.history("objective-invalid-action-selection").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                        .allMatch(event -> event.detail().contains("bounded-local-retry")),
                "recovery must go through the ordinary bounded-local-retry path, not a replan or human escalation");
    }

    @Test
    void deterministicContractFailureEscalatesOnFirstAttemptWithoutBurningTheBoundedRetryBudget() {
        // Root-cause fix: a failure that will recur identically on an unmodified retry (a
        // deterministically oversized cognition request, an unsupported/missing project system, or
        // CognitiveWorkerRuntime's own repeated-failure circuit breaker) was previously blindly
        // bounded-locally-retried up to MAX_MUTATING_DISPATCH_ATTEMPTS times before escalating -- wasted
        // work against a request that could never succeed, and a delayed escalation for the exact same
        // Human review it should have reached on the very first attempt.
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T14:00:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger executions = new AtomicInteger();

        AutonomousExecutionCapability deterministicallyBroken = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return GeneralWorkspaceAutonomousCapability.CAPABILITY; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                executions.incrementAndGet();
                throw new IllegalStateException("workspace build system not detected");
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(deterministicallyBroken), coordination, clock,
                "runner-deterministic-contract", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-deterministic-contract", "worker-head", "org-metatron",
                "Build a project with no supported build system",
                "human:founder", "request-admission:deterministic-contract", "case-deterministic-contract",
                "conversation-deterministic-contract", "message-deterministic-contract", "telegram",
                generalWorkspaceRequest(), clock.instant());

        runner.runOnce();

        assertEquals(1, executions.get(),
                "a deterministic-contract failure must never consume the bounded-retry budget: it cannot "
                        + "succeed on an unmodified retry, so it must escalate on the very first attempt");
        assertEquals(ManagementObjective.Status.BLOCKED, management.get("objective-deterministic-contract").status());
        assertEquals(0, management.history("objective-deterministic-contract").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                        .count(),
                "no bounded-local-retry recovery event may be recorded for a deterministic-contract failure");
    }

    private static NormalizedRequest generalWorkspaceRequest() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-1", "Build the workspace", "target", GeneralWorkspaceAutonomousCapability.CAPABILITY, List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("build succeeds"), List.of("build evidence"));
        return new NormalizedRequest(
                "Build the workspace", "target", List.of("mutating"), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of("mutate the workspace"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
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

    private static NormalizedRequest twoStepRequest() {
        ExecutionWorkSpec stepOk = new ExecutionWorkSpec(
                "step-ok", "Perform a routine read that always succeeds", "target",
                "test.recovery.read.ok", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("read completes"), List.of("execution evidence"));
        ExecutionWorkSpec stepFlaky = new ExecutionWorkSpec(
                "step-flaky", "Perform a recoverable read that depends on step-ok", "target",
                "test.recovery.read.flaky", List.of("step-ok"), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("read completes"), List.of("execution evidence"));
        return new NormalizedRequest(
                "Perform two dependent reads", "target", List.of("read-only"), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of("do not mutate"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(stepOk, stepFlaky), false, null, LlmProvider.OPENAI, "");
    }
}
