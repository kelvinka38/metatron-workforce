package com.metatron.workforce.phase6;

import com.metatron.workforce.phase5.ExecutionFeasibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionServiceTest {
    private static final Instant REQUESTED = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant APPROVED = Instant.parse("2026-08-20T10:05:00Z");
    private static final Instant STARTED = Instant.parse("2026-08-20T10:10:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-08-20T10:11:00Z");

    private WorkProposal proposal() {
        return new WorkProposal("proposal-001", "worker-001", "work-001", "EXECUTE_WORK", "farm-A", "org-A", REQUESTED);
    }

    private ApprovalDecision approval(boolean approved) {
        return new ApprovalDecision("decision-001", "proposal-001", "head-001", approved, APPROVED,
                "authority-001", approved ? "approved" : "rejected");
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest("worker-001", "publisher", "authority-001", "farm-A", "EXECUTE_WORK",
                "org-A", STARTED, "resource-001");
    }

    private AuthorizationService authorization(boolean allowed) {
        return new AuthorizationService(ignored -> allowed
                ? Phase6AuthorizationPolicy.Decision.allowed("auth-001")
                : Phase6AuthorizationPolicy.Decision.denied("policy-001", "forbidden"));
    }

    private ExecutionFeasibility feasible() {
        return new ExecutionFeasibility(ExecutionFeasibility.Status.FEASIBLE, 0, 0, List.of());
    }

    @Test
    void validExecutionCompletesAndPreservesAuthorization() {
        ExecutionRecord record = new ExecutionService().execute(
                proposal(), approval(true), request(), authorization(true), feasible(),
                p -> ExecutionService.ExecutionResult.success(List.of("input"), List.of("output")),
                "execution-001", STARTED, COMPLETED, null);

        assertEquals(ExecutionRecord.Status.SUCCEEDED, record.status());
        assertEquals("worker-001", record.actorId());
        assertEquals("auth-001", record.authorizationReference());
        assertEquals(List.of("input"), record.inputs());
        assertEquals(List.of("output"), record.outputs());
    }

    @Test
    void unauthorizedExecutionFailsClosed() {
        assertThrows(SecurityException.class, () -> new ExecutionService().execute(
                proposal(), approval(true), request(), authorization(false), feasible(),
                p -> fail("executor must not run"), "execution-002", STARTED, COMPLETED, null));
    }

    @Test
    void insufficientRealityBlocksExecutionBeforeExecutorRuns() {
        ExecutionFeasibility blocked = new ExecutionFeasibility(
                ExecutionFeasibility.Status.BLOCKED, 0, 1, List.of("qualified-worker-unavailable"));

        ExecutionRecord record = new ExecutionService().execute(
                proposal(), approval(true), request(), authorization(true), blocked,
                p -> fail("executor must not run"), "execution-003", STARTED, COMPLETED, null);

        assertEquals(ExecutionRecord.Status.BLOCKED, record.status());
        assertEquals("qualified-worker-unavailable", record.failureReason());
    }

    @Test
    void partialRealityRemainsPartial() {
        ExecutionFeasibility partial = new ExecutionFeasibility(
                ExecutionFeasibility.Status.PARTIAL, 2.5, 1, List.of());

        ExecutionRecord record = new ExecutionService().execute(
                proposal(), approval(true), request(), authorization(true), partial,
                p -> fail("executor must not run"), "execution-004", STARTED, COMPLETED, null);

        assertEquals(ExecutionRecord.Status.PARTIAL, record.status());
    }

    @Test
    void failureIsRecordedAsFailure() {
        ExecutionRecord record = new ExecutionService().execute(
                proposal(), approval(true), request(), authorization(true), feasible(),
                p -> ExecutionService.ExecutionResult.failure(List.of("input"), "downstream unavailable"),
                "execution-005", STARTED, COMPLETED, null);

        assertEquals(ExecutionRecord.Status.FAILED, record.status());
        assertEquals("downstream unavailable", record.failureReason());
    }

    @Test
    void retryRemainsLinkedToOriginalExecution() {
        ExecutionRecord retry = new ExecutionRecord(
                "execution-006b", "proposal-001", "worker-001", "EXECUTE_WORK", STARTED, COMPLETED,
                "org-A", "auth-006", List.of("input"), List.of(), ExecutionRecord.Status.FAILED,
                "timeout", "execution-006a");

        assertEquals("execution-006a", retry.retryOfExecutionId());
        assertNotEquals(retry.executionId(), retry.retryOfExecutionId());
    }
}
