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
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanObjectiveIngressServiceTest {

    @Test
    void missingCapabilityBecomesStaffingBlockerWithoutMintingExecutionAuthorization() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(), "worker-head",
                Clock.fixed(Instant.parse("2026-08-30T03:00:00Z"), ZoneOffset.UTC));

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
        assertEquals("objective:intelligence-case:case-001", receipt.objectiveId());
        assertEquals("worker-head", receipt.ownerWorkerId());
        assertEquals("BLOCKED", receipt.objectiveStatus());
        assertEquals("STAFFING_REQUIRED:UNAVAILABLE:repository.write", receipt.executionAdmissionState());
        assertEquals("REQUIRED_CAPABILITY_UNAVAILABLE", receipt.reason());
        assertFalse(receipt.queueItemId().isBlank());

        ManagementObjective objective = management.get(receipt.objectiveId());
        assertEquals("worker-head", objective.ownerWorkerId());
        assertTrue(objective.description().contains("Fix admitted defects"));
        assertTrue(objective.description().contains("Ingress: telegram/telegram:update:1001"));

        String acceptanceDetail = management.history(receipt.objectiveId()).getFirst().detail();
        assertTrue(acceptanceDetail.contains("request_admission=workplace-request-admission:human-primary:worker-head"));
        assertTrue(acceptanceDetail.contains("execution_authorization=NONE"));
        assertFalse(acceptanceDetail.contains("authorization:workplace-request-only"));
        assertTrue(management.history(receipt.objectiveId()).stream()
                .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.STAFFING_NEED_DETECTED));
    }

    @Test
    void capabilityBackedWorkExecutesDeliversEvidenceAndRetryIsIdempotent() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability fake = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                executions.incrementAndGet();
                return new CapabilityResult(true, "worker-auditor", "assignment-1", "work-1",
                        List.of("evidence:test-pass"), "PASS");
            }
        };
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(fake), "worker-head", Clock.systemUTC());
        NormalizedRequest request = executionRequest("Audit repository", new ExecutionWorkSpec(
                "step-1", "Audit repository", "kelvinka38/metatron-workforce",
                "test.audit.read", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY));

        assertEquals(List.of("test.audit.read"), ingress.capabilityCatalog());
        ExecutionObjectiveHandoff.HandoffReceipt first = ingress.submit(
                "human-primary", "org-metatron", "case-retry", "conversation-1",
                "telegram:update:2001", "telegram", request);
        ExecutionObjectiveHandoff.HandoffReceipt second = ingress.submit(
                "human-primary", "org-metatron", "case-retry", "conversation-1",
                "telegram:update:2001", "telegram", request);

        assertEquals("DELIVERED", first.objectiveStatus());
        assertEquals("COMPLETED", first.executionAdmissionState());
        assertEquals("WORK_COMPLETED_BY_WORKFORCE", first.reason());
        assertEquals(first.objectiveId(), second.objectiveId());
        assertEquals(first.queueItemId(), second.queueItemId());
        assertEquals("WORK_ALREADY_COMPLETED_BY_WORKFORCE", second.reason());
        assertEquals(1, executions.get());
        assertEquals(1, management.allObjectives().size());
        assertEquals(List.of("assignment-1"), management.get(first.objectiveId()).assignmentRefs());
        assertTrue(management.get(first.objectiveId()).evidenceRefs().contains("evidence:test-pass"));
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
