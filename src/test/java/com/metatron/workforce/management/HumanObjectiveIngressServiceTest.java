package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanObjectiveIngressServiceTest {

    @Test
    void missingExecutionCapabilityTriggersBoundedReplanWithoutMintingAuthorizationOrFakeStaffing() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T03:00:00Z"), ZoneOffset.UTC);
        AutonomousManagementRunner runner = runner(management, List.of(), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(), runner, "worker-head", clock);

        ExecutionObjectiveHandoff.HandoffReceipt receipt = ingress.submit(
                "human-primary",
                "org-metatron",
                "case-001",
                "conversation:human:human-primary",
                "telegram:update:1001",
                "telegram",
                executionRequest("Fix admitted defects", new ExecutionWorkSpec(
                        "step-1", "Modify repository defects", "kelvinka38/metatron-workforce",
                        "UNAVAILABLE:repository.write", List.of(), ExecutionWorkSpec.Consequence.MUTATING)));

        assertTrue(receipt.accepted());
        assertEquals("objective:intelligence-case:case-001:request:telegram:update:1001", receipt.objectiveId());
        assertEquals("worker-head", receipt.ownerWorkerId());
        assertEquals("ACCEPTED", receipt.objectiveStatus());
        assertEquals("ACCEPTED", receipt.executionAdmissionState());
        assertEquals("OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT", receipt.reason());
        assertFalse(receipt.queueItemId().isBlank());

        runner.runOnce();

        ManagementObjective objective = management.get(receipt.objectiveId());
        assertEquals(ManagementObjective.Status.REPLANNING, objective.status());
        assertEquals("worker-head", objective.ownerWorkerId());
        assertTrue(objective.description().contains("Fix admitted defects"));
        assertTrue(objective.description().contains("Case: case-001"));
        assertTrue(objective.description().contains("Conversation: conversation:human:human-primary"));
        assertTrue(objective.description().contains("Ingress: telegram/telegram:update:1001"));

        String acceptanceDetail = management.history(receipt.objectiveId()).getFirst().detail();
        assertTrue(acceptanceDetail.contains("request_admission=workplace-request-admission:human-primary:worker-head"));
        assertTrue(acceptanceDetail.contains("execution_authorization=NONE"));
        assertFalse(acceptanceDetail.contains("authorization:workplace-request-only"));
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.REPLAN_REQUESTED
                        && event.detail().contains("execution-capability-unavailable")));
        assertFalse(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.STAFFING_NEED_DETECTED));
        assertFalse(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.detail().contains("staffing-required:UNAVAILABLE:repository.write")));
    }

    @Test
    void capabilityBackedWorkExecutesDeliversEvidenceAndSameRequestRetryIsIdempotent() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability fake = successfulCapability(executions);
        AutonomousManagementRunner runner = runner(management, List.of(fake), Clock.systemUTC());
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(fake), runner, "worker-head", Clock.systemUTC());
        NormalizedRequest request = auditRequest();

        assertEquals(List.of("test.audit.read"), ingress.capabilityCatalog());
        ExecutionObjectiveHandoff.HandoffReceipt first = ingress.submit(
                "human-primary", "org-metatron", "case-retry", "conversation-1",
                "telegram:update:2001", "telegram", request);
        assertEquals("ACCEPTED", first.objectiveStatus());
        assertEquals("ACCEPTED", first.executionAdmissionState());
        assertEquals(0, executions.get());

        runner.runOnce();
        ExecutionObjectiveHandoff.HandoffReceipt second = ingress.submit(
                "human-primary", "org-metatron", "case-retry", "conversation-1",
                "telegram:update:2001", "telegram", request);

        assertEquals(first.objectiveId(), second.objectiveId());
        assertEquals(first.queueItemId(), second.queueItemId());
        assertEquals("COMPLETED", second.objectiveStatus());
        assertEquals("COMPLETED", second.executionAdmissionState());
        assertEquals("WORK_ALREADY_COMPLETED_BY_WORKFORCE", second.reason());
        assertEquals(1, executions.get());
        assertEquals(1, management.allObjectives().size());
        assertEquals(List.of("assignment-1"), management.get(first.objectiveId()).assignmentRefs());
        assertTrue(management.get(first.objectiveId()).evidenceRefs().contains("evidence:test-pass"));
    }

    @Test
    void targetedWorkerOwnsDurableObjectiveAndReplayCannotChangeOwner() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        Clock clock = Clock.systemUTC();
        AutonomousManagementRunner runner = runner(management, List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, null,
                workerId -> {
                    if (workerId.equals("worker-special") || workerId.equals("worker-head")) return workerId;
                    throw new IllegalStateException("worker_not_active:" + workerId);
                },
                "worker-head", clock);

        ExecutionObjectiveHandoff.HandoffReceipt first = ingress.submitToWorker(
                "worker-special",
                "human-primary", "org-metatron", "case-worker-owned", "conversation-worker",
                "telegram:update:worker-1", "telegram", auditRequest());

        assertTrue(first.accepted());
        assertEquals("worker-special", first.ownerWorkerId());
        assertEquals("worker-special", management.get(first.objectiveId()).ownerWorkerId());
        assertTrue(management.history(first.objectiveId()).getFirst().detail()
                .contains("request_admission=workplace-request-admission:human-primary:worker-special"));

        runner.runOnce();
        assertEquals(ManagementObjective.Status.COMPLETED, management.get(first.objectiveId()).status());
        assertEquals(1, executions.get());

        SecurityException replay = assertThrows(SecurityException.class, () -> ingress.submitToWorker(
                "worker-head",
                "human-primary", "org-metatron", "case-worker-owned", "conversation-worker",
                "telegram:update:worker-1", "telegram", auditRequest()));
        assertTrue(replay.getMessage().contains("objective owner mismatch on replay"));
        assertThrows(IllegalStateException.class, () -> ingress.submitToWorker(
                "worker-not-canonical",
                "human-primary", "org-metatron", "case-worker-owned-2", "conversation-worker",
                "telegram:update:worker-2", "telegram", auditRequest()));
    }

    @Test
    void newExecutionRequestInSameCaseGetsDistinctObjectiveAndCurrentProviderCorrelation() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        AutonomousManagementRunner runner = runner(management, List.of(capability), Clock.systemUTC());
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", Clock.systemUTC());
        NormalizedRequest request = auditRequest();

        ExecutionObjectiveHandoff.HandoffReceipt first = ingress.submit(
                "human-primary", "org-metatron", "case-shared", "conversation-1",
                "telegram:update:3001", "telegram", request);
        ExecutionObjectiveHandoff.HandoffReceipt second = ingress.submit(
                "human-primary", "org-metatron", "case-shared", "conversation-1",
                "zalo:message:9002", "zalo", request);

        assertEquals(0, executions.get());
        runner.runOnce();
        assertNotEquals(first.objectiveId(), second.objectiveId());
        assertNotEquals(first.queueItemId(), second.queueItemId());
        assertEquals(2, executions.get());
        assertEquals(2, management.allObjectives().size());
        assertTrue(management.get(first.objectiveId()).description().contains("telegram:update:3001"));
        assertTrue(management.get(second.objectiveId()).description().contains("zalo:message:9002"));
        assertTrue(first.objectiveId().contains("telegram:update:3001"));
        assertTrue(second.objectiveId().contains("zalo:message:9002"));
        assertTrue(management.history(second.objectiveId()).getFirst().detail().contains("execution_authorization=NONE"));
    }

    private static AutonomousManagementRunner runner(ManagementAutonomyService management,
                                                     List<AutonomousExecutionCapability> capabilities,
                                                     Clock clock) {
        return new AutonomousManagementRunner(management,
                (caseId, request, available) -> request.executionWorkPlan(), capabilities, clock);
    }

    private static AutonomousExecutionCapability successfulCapability(AtomicInteger executions) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                executions.incrementAndGet();
                return new CapabilityResult(true, "worker-auditor", "assignment-1", "work-1",
                        List.of("evidence:test-pass"), "PASS");
            }
        };
    }

    private static NormalizedRequest auditRequest() {
        return executionRequest("Audit repository", new ExecutionWorkSpec(
                "step-1", "Audit repository", "kelvinka38/metatron-workforce",
                "test.audit.read", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY));
    }

    private static NormalizedRequest executionRequest(String objective, ExecutionWorkSpec step) {
        return new NormalizedRequest(
                objective,
                "metatron-workforce",
                List.of("preserve evidence"),
                IntelligenceDepth.ANALYZE,
                "terminal result",
                List.of(),
                List.of("do not bypass authorization"),
                "current",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(step),
                false,
                null,
                LlmProvider.OPENAI,
                "");
    }
}
