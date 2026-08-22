package com.metatron.workforce.phase10;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class Phase10PrimaryVerticalSliceAcceptanceTest {

    private final Phase10PrimaryVerticalSliceService service = new Phase10PrimaryVerticalSliceService();

    @Test
    void completePrimarySliceRunsFromHumanRequestToNextCycle() {
        VerticalSliceRequest request = new VerticalSliceRequest(
                "REQ-001", "HUMAN-001", "HEAD-WORKFORCE",
                "Prepare next month's farm operating plan.", Instant.now());

        VerticalSliceResult result = service.run(
                request, "FARM-001",
                160, 8, 24,
                100_000, 2_000_000,
                100, 110, 17_000_000,
                "8 workers x 20h/week", "weather and maintenance risk");

        assertEquals("Prepare next month's farm operating plan.", result.request().instruction());
        assertEquals(160, result.plan().requiredLaborHours());
        assertEquals(192, result.plan().availableLaborHours());
        assertEquals(0, result.plan().capacityDeficitHours());
        assertEquals(Approval.Status.APPROVED, result.approval().status());
        assertTrue(result.approval().authorityReference().contains("HEAD-WORKFORCE"));
        assertTrue(result.execution().success());
        assertEquals(110, result.execution().actualOutput());
        assertEquals("TARGET_MET", result.report().performance());
        assertEquals(10, result.report().outputVariance());
        assertTrue(result.learning().validated());
        assertEquals(110, result.nextPlan().expectedOutput());
        assertEquals("PENDING_HUMAN_REVIEW", result.reviewStatus());

        VerticalSliceResult reviewed = service.review(result, "APPROVED_FOR_NEXT_CYCLE");
        assertEquals("APPROVED_FOR_NEXT_CYCLE", reviewed.reviewStatus());
    }

    @Test
    void capacityDeficitBlocksExecutionInsteadOfManufacturingSuccess() {
        FarmOperatingPlan plan = service.plan(
                "PLAN-BLOCKED", "FARM-001",
                200, 4, 24,
                100_000, 1_000_000, 100,
                "4 workers x 24h", "understaffed", "test-plan");
        Approval approval = service.approve(plan, "AUTHORITY:HEAD-WORKFORCE", "test-approval");

        ExecutionOutcome outcome = service.execute(
                plan, approval, 200, 20_000_000, 100, "test-execution");

        assertFalse(outcome.success());
        assertTrue(outcome.failure().contains("capacity deficit"));
    }

    @Test
    void missingAuthorityCannotProduceApproval() {
        FarmOperatingPlan plan = service.plan(
                "PLAN-AUTH", "FARM-001",
                10, 1, 24,
                100_000, 0, 10,
                "one shift", "none", "test-plan");

        assertThrows(IllegalArgumentException.class,
                () -> service.approve(plan, "", "test-approval"));
    }

    @Test
    void failedExecutionCannotBecomeSuccessfulLearning() {
        FarmOperatingPlan plan = service.plan(
                "PLAN-FAIL", "FARM-001",
                10, 1, 24,
                100_000, 0, 100,
                "one shift", "none", "test-plan");
        Approval approval = service.approve(plan, "AUTHORITY:HEAD-WORKFORCE", "test-approval");
        ExecutionOutcome outcome = service.execute(
                plan, approval, 0, 0, 0, "test-execution");
        CycleReport report = service.report(plan, outcome, "test-report");
        LearningImprovement learning = service.learn(
                report, "execution-failure", "do not adopt failed execution", true, "test-learning");

        assertFalse(outcome.success());
        assertEquals("EXECUTION_FAILED", report.performance());
        assertFalse(learning.validated());
        assertEquals(plan.expectedOutput(), learning.improvedOutput());
    }

    @Test
    void provenanceIsRequiredAcrossMaterialStages() {
        FarmOperatingPlan plan = service.plan(
                "PLAN-PROV", "FARM-001",
                10, 1, 24,
                100_000, 0, 10,
                "one shift", "none", "request=REQ-PROV");
        Approval approval = service.approve(plan, "AUTHORITY:HEAD-WORKFORCE", "request=REQ-PROV");
        ExecutionOutcome execution = service.execute(plan, approval, 10, 1_000_000, 10, "approval=AUTHORITY:HEAD-WORKFORCE");
        CycleReport report = service.report(plan, execution, "execution=" + execution.executionId());

        assertFalse(plan.provenance().isBlank());
        assertFalse(approval.provenance().isBlank());
        assertFalse(execution.provenance().isBlank());
        assertFalse(report.provenance().isBlank());
    }
}
