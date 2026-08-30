package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanObjectiveIngressServiceTest {

    @Test
    void semanticExecutionRequestBecomesHeadOwnedObjectiveWithoutMintingExecutionAuthorization() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, "worker-head", Clock.fixed(Instant.parse("2026-08-30T03:00:00Z"), ZoneOffset.UTC));

        ExecutionObjectiveHandoff.HandoffReceipt receipt = ingress.submit(
                "human-primary",
                "org-metatron",
                "case-001",
                "conversation:human:human-primary",
                "telegram:update:1001",
                "telegram",
                executionRequest("Audit all canonical repositories and fix admitted defects"));

        assertTrue(receipt.accepted());
        assertEquals("objective:intelligence-case:case-001", receipt.objectiveId());
        assertEquals("worker-head", receipt.ownerWorkerId());
        assertEquals("ACTIVE", receipt.objectiveStatus());
        assertEquals("AWAITING_ASSIGNMENT_AUTHORIZATION_AND_EXECUTION_ADMISSION", receipt.executionAdmissionState());
        assertFalse(receipt.queueItemId().isBlank());

        ManagementObjective objective = management.get(receipt.objectiveId());
        assertEquals("worker-head", objective.ownerWorkerId());
        assertTrue(objective.description().contains("Audit all canonical repositories"));
        assertTrue(objective.description().contains("Ingress: telegram/telegram:update:1001"));

        String acceptanceDetail = management.history(receipt.objectiveId()).getFirst().detail();
        assertTrue(acceptanceDetail.contains("authority=authority:workplace-request-intake"));
        assertTrue(acceptanceDetail.contains("authorization=authorization:workplace-request-only:telegram:update:1001"));
    }

    @Test
    void retryOfSameIntelligenceCaseIsIdempotentForObjectiveAndQueue() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, "worker-head", Clock.systemUTC());
        NormalizedRequest request = executionRequest("Deploy only after admitted authorization");

        ExecutionObjectiveHandoff.HandoffReceipt first = ingress.submit(
                "human-primary", "org-metatron", "case-retry", "conversation-1",
                "telegram:update:2001", "telegram", request);
        ExecutionObjectiveHandoff.HandoffReceipt second = ingress.submit(
                "human-primary", "org-metatron", "case-retry", "conversation-1",
                "telegram:update:2001", "telegram", request);

        assertEquals(first.objectiveId(), second.objectiveId());
        assertEquals(first.queueItemId(), second.queueItemId());
        assertEquals(1, management.allObjectives().size());
    }

    private static NormalizedRequest executionRequest(String objective) {
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
                false,
                null,
                LlmProvider.OPENAI,
                "");
    }
}
