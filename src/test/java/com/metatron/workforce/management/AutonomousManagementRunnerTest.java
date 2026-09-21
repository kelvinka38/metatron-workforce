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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousManagementRunnerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void acceptedObjectiveSurvivesServiceReplacementAndCompletesInBackground() {
        Path statePath = temporaryDirectory.resolve("management-state.json");
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = capability(executions);

        ManagementAutonomyService firstProcess = new ManagementAutonomyService(
                new FileManagementStateStore(statePath));
        AutonomousManagementRunner dormantRunner = runner(firstProcess, capability, clock, "runner-first");
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                firstProcess, List.of(capability), dormantRunner, "worker-head", clock);

        var receipt = ingress.submit("human-primary", "org-metatron", "case-restart",
                "conversation-1", "telegram:update:restart", "telegram", request());
        assertEquals("ACCEPTED", receipt.objectiveStatus());
        assertEquals(0, executions.get());

        ManagementAutonomyService replacementProcess = new ManagementAutonomyService(
                new FileManagementStateStore(statePath));
        AutonomousManagementRunner replacementRunner = runner(
                replacementProcess, capability, clock, "runner-replacement");
        replacementRunner.runOnce();

        assertEquals(1, executions.get());
        assertEquals(ManagementObjective.Status.COMPLETED,
                replacementProcess.get(receipt.objectiveId()).status());
        assertTrue(replacementProcess.get(receipt.objectiveId()).evidenceRefs()
                .contains("evidence:restart-pass"));
        assertTrue(replacementProcess.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveAccepted")));
        assertTrue(replacementProcess.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveCompleted")));
        assertTrue(replacementProcess.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveOutcomeReportReady")
                        && message.payload().contains("status=COMPLETED")
                        && message.payload().contains("evidence:restart-pass")));
        assertTrue(replacementProcess.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPORT_READY));
    }

    @Test
    void successfulCapabilityWithoutEvidenceBlocksObjectiveCompletion() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomousExecutionCapability capability = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, "worker-auditor", "assignment-no-evidence",
                        "work-no-evidence", List.of(), "PASS");
            }
        };
        AutonomousManagementRunner runner = runner(management, capability, clock, "runner-evidence-gate");
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        var receipt = ingress.submit("human-primary", "org-metatron", "case-no-evidence",
                "conversation-no-evidence", "message-no-evidence", "chat", request());
        runner.runOnce();

        assertEquals(ManagementObjective.Status.BLOCKED, management.get(receipt.objectiveId()).status());
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.BLOCKED
                        && event.detail().contains("reason=evidence_missing")));
        assertFalse(management.outbox().stream()
                .anyMatch(message -> message.messageType().equals("ObjectiveCompleted")));
    }

    @Test
    void hungReadOnlyDispatchTimesOutAndBoundedlyRetriesInsteadOfFreezingTheRunner() {
        // Production incident (2026-09-21): a single node dispatch blocked forever inside a
        // governed capability call. AutonomousManagementRunner.await() had no timeout on
        // future.get(), and runOnce()'s exclusive lock is held for the whole pass, so that one
        // hung dispatch silently froze the ENTIRE runner -- every objective, not just the stuck
        // one -- with zero further log output until an operator manually restarted the process.
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomousExecutionCapability hangs = hangingCapability("test.read.hangs");
        AutonomousManagementRunner runner = runnerWithNodeTimeout(
                management, hangs, clock, "runner-hung-read-only", Duration.ofMillis(50));
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(hangs), runner, "worker-head", clock);

        var receipt = ingress.submit("human-primary", "org-metatron", "case-hung-read-only",
                "conversation-hung", "message-hung", "chat",
                request("test.read.hangs", ExecutionWorkSpec.Consequence.READ_ONLY));

        assertTimeoutPreemptively(Duration.ofSeconds(10), runner::runOnce,
                "a single hung dispatch must never block runOnce() beyond its configured node timeout");

        assertEquals(ManagementObjective.Status.BLOCKED, management.get(receipt.objectiveId()).status());
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.BLOCKED
                        && event.detail().contains("node-execution-timeout")),
                "exhausted read-only retries must surface the timeout, not silently vanish");
    }

    @Test
    void hungMutatingDispatchTimesOutAndBlocksForReconciliationRatherThanAutoReplanning() {
        // A MUTATING dispatch that times out may still be running in the background (cancel(true)
        // best-effort-interrupts it, but cannot guarantee termination), so it must never be
        // auto-replanned -- a fresh replan could dispatch a duplicate concurrent mutation. It
        // surfaces as a governed block instead, exactly like any other unsafe interrupted
        // mutating dispatch, so a Human/operator resolves it via recoverLocally().
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomousExecutionCapability hangs = hangingCapability("test.mutation.hangs");
        AutonomousManagementRunner runner = runnerWithNodeTimeout(
                management, hangs, clock, "runner-hung-mutating", Duration.ofMillis(50));
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(hangs), runner, "worker-head", clock);

        var receipt = ingress.submit("human-primary", "org-metatron", "case-hung-mutating",
                "conversation-hung-mutating", "message-hung-mutating", "chat",
                request("test.mutation.hangs", ExecutionWorkSpec.Consequence.MUTATING));

        assertTimeoutPreemptively(Duration.ofSeconds(10), runner::runOnce,
                "a single hung mutating dispatch must never block runOnce() beyond its configured node timeout");

        assertEquals(ManagementObjective.Status.BLOCKED, management.get(receipt.objectiveId()).status());
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.BLOCKED
                        && event.detail().contains("node-execution-timeout")),
                "the timed-out mutating dispatch must surface for reconciliation");
        assertFalse(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED),
                "a mutating timeout must never be auto-replanned while the dispatch might still be running");
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleRunnerIsFenced() {
        Instant acceptedAt = Instant.parse("2026-08-31T00:00:00Z");
        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptHumanObjective("objective-lease", "worker-head", "org-metatron",
                "Lease recovery", "human:primary", "request-admission", "case-lease",
                "conversation-lease", "message-lease", "api", request(), acceptedAt);

        ManagementLease first = management.acquireManagementLease(
                "objective-lease", "runner-a", Duration.ofMinutes(1), acceptedAt).orElseThrow();
        assertTrue(management.acquireManagementLease("objective-lease", "runner-b",
                Duration.ofMinutes(1), acceptedAt.plusSeconds(30)).isEmpty());

        ManagementLease replacement = management.acquireManagementLease(
                "objective-lease", "runner-b", Duration.ofMinutes(1), acceptedAt.plusSeconds(61)).orElseThrow();
        assertTrue(replacement.fencingVersion() > first.fencingVersion());
        assertThrows(IllegalStateException.class, () -> management.beginPlanning(
                "objective-lease", "runner-a", first.token(), acceptedAt.plusSeconds(62)));
        assertFalse(replacement.token().equals(first.token()));
    }

    private static AutonomousManagementRunner runner(ManagementAutonomyService management,
                                                      AutonomousExecutionCapability capability,
                                                      Clock clock, String runnerId) {
        return new AutonomousManagementRunner(management,
                (caseId, request, available) -> request.executionWorkPlan(),
                List.of(capability), clock, runnerId, Duration.ofMinutes(5), Duration.ofSeconds(5));
    }

    private static AutonomousManagementRunner runnerWithNodeTimeout(ManagementAutonomyService management,
                                                                     AutonomousExecutionCapability capability,
                                                                     Clock clock, String runnerId,
                                                                     Duration nodeExecutionTimeout) {
        return runner(management, capability, clock, runnerId)
                .configureNodeExecutionTimeout(nodeExecutionTimeout);
    }

    /** Blocks forever until its dispatch is cancelled/interrupted, simulating a stuck governed call. */
    private static AutonomousExecutionCapability hangingCapability(String capabilityRef) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return capabilityRef; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new CapabilityResult(true, "worker-auditor", "assignment-hung",
                        "work-hung", List.of("evidence:should-not-be-observed"), "PASS");
            }
        };
    }

    private static AutonomousExecutionCapability capability(AtomicInteger executions) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }

            @Override
            public CapabilityResult execute(CapabilityRequest request) {
                executions.incrementAndGet();
                return new CapabilityResult(true, "worker-auditor", "assignment-restart",
                        "work-restart", List.of("evidence:restart-pass"), "PASS");
            }
        };
    }

    private static NormalizedRequest request() {
        return request("test.audit.read", ExecutionWorkSpec.Consequence.READ_ONLY);
    }

    private static NormalizedRequest request(String capabilityRef, ExecutionWorkSpec.Consequence consequence) {
        ExecutionWorkSpec step = new ExecutionWorkSpec("step-1", "Audit repository",
                "kelvinka38/metatron-workforce", capabilityRef, List.of(), consequence);
        return new NormalizedRequest("Audit repository", "metatron-workforce",
                List.of("read-only"), IntelligenceDepth.ANALYZE, "evidence-backed result",
                List.of(), List.of("do not mutate"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
