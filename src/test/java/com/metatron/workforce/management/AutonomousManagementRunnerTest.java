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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void plannerCannotPersistWorkOutsideRealCapabilityDomain() {
        Clock clock = Clock.systemUTC();
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger effects = new AtomicInteger();
        AutonomousExecutionCapability capability = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public boolean supportsWork(ExecutionWorkSpec workSpec) { return false; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                return new CapabilityResult(true, "worker", "assignment", "work", List.of(), "unexpected");
            }
        };
        AutonomousManagementRunner runner = runner(management, capability, clock, "runner-domain");
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);
        var receipt = ingress.submit("human-primary", "org-metatron", "case-domain",
                "conversation-domain", "telegram:update:domain", "telegram", request());

        runner.runOnce();

        assertEquals(0, effects.get());
        assertEquals(ManagementObjective.Status.BLOCKED, management.get(receipt.objectiveId()).status());
        assertTrue(management.findAutonomousWork(receipt.objectiveId()).orElseThrow().blocker()
                .contains("capability-domain-mismatch"));
    }

    @Test
    void longObjectiveDoesNotStarveAnotherRunnableObjective() throws Exception {
        Clock clock = Clock.systemUTC();
        ManagementAutonomyService management = new ManagementAutonomyService();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastCompleted = new CountDownLatch(1);
        AutonomousExecutionCapability capability = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                if (request.objectiveId().contains("slow")) {
                    slowStarted.countDown();
                    try {
                        if (!releaseSlow.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("slow release timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                } else {
                    fastCompleted.countDown();
                }
                return new CapabilityResult(true, "worker-auditor", "assignment:" + request.objectiveId(),
                        "work:" + request.objectiveId(), List.of("evidence:" + request.objectiveId()), "PASS");
            }
        };
        AutonomousManagementRunner runner = runner(management, capability, clock, "runner-concurrent");
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);
        ingress.submit("human-primary", "org-metatron", "case-slow", "conversation-slow",
                "telegram:update:slow", "telegram", request());
        ingress.submit("human-primary", "org-metatron", "case-fast", "conversation-fast",
                "telegram:update:fast", "telegram", request());

        try {
            runner.start();
            assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
            assertTrue(fastCompleted.await(2, TimeUnit.SECONDS),
                    "a long Objective must not starve another runnable Objective");
        } finally {
            releaseSlow.countDown();
            runner.close();
        }
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
        ExecutionWorkSpec step = new ExecutionWorkSpec("step-1", "Audit repository",
                "kelvinka38/metatron-workforce", "test.audit.read", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY);
        return new NormalizedRequest("Audit repository", "metatron-workforce",
                List.of("read-only"), IntelligenceDepth.ANALYZE, "evidence-backed result",
                List.of(), List.of("do not mutate"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
