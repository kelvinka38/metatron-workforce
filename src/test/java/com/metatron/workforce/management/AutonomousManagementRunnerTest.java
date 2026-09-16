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
