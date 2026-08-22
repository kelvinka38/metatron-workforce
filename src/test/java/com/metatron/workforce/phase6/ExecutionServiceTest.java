package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionServiceTest {
    private static final Instant T0 = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-20T10:05:00Z");
    private static final Instant T2 = Instant.parse("2026-08-20T11:00:00Z");

    private AuthorizationDecision authorization() {
        return new AuthorizationDecision(
                "auth-req-001", "req-001", AuthorizationDecision.Outcome.ALLOW,
                "policy allows", "authority-001", null, "policy-001",
                T0, T2, T0, "head-001", "evidence-001");
    }

    private Execution requested() {
        return Execution.requested(
                "exec-001", "req-001", "worker-001", "assignment-001",
                "auth-req-001", T0, "evidence-exec-001");
    }

    @Test
    void validExecutionMovesToRunningAndCompletes() {
        ExecutionService service = new ExecutionService();
        Execution execution = service.validating(requested());
        execution = service.admit(execution, authorization(), T1);
        execution = service.start(execution, T1, ignored -> true);
        execution = service.complete(execution, T2, "work completed");

        assertEquals(Execution.State.COMPLETED, execution.state());
        assertEquals(T1, execution.startedAt());
        assertEquals(T2, execution.completedAt());
        assertTrue(execution.terminal());
    }

    @Test
    void expiredOrMismatchedAuthorizationCannotAdmitExecution() {
        ExecutionService service = new ExecutionService();
        Execution execution = service.validating(requested());

        AuthorizationDecision expired = new AuthorizationDecision(
                "auth-req-001", "req-001", AuthorizationDecision.Outcome.ALLOW,
                "expired", "authority-001", null, "policy-001",
                T0, T1, T0, "head-001", "evidence-001");

        assertThrows(IllegalStateException.class,
                () -> service.admit(execution, expired, T2));
    }

    @Test
    void insufficientRealityConstraintsBlockExecution() {
        ExecutionService service = new ExecutionService();
        Execution execution = service.validating(requested());
        execution = service.admit(execution, authorization(), T1);
        execution = service.start(execution, T1, ignored -> false);

        assertEquals(Execution.State.BLOCKED, execution.state());
        assertFalse(execution.terminal());
        assertNotNull(execution.failureReason());
    }

    @Test
    void failureAndCancellationRemainTerminalAndDistinct() {
        ExecutionService service = new ExecutionService();
        Execution running = service.start(
                service.admit(service.validating(requested()), authorization(), T1),
                T1, ignored -> true);

        Execution failed = service.fail(running, T2, "external dependency failed");
        assertEquals(Execution.State.FAILED, failed.state());
        assertTrue(failed.terminal());

        Execution runningAgain = service.start(
                service.admit(service.validating(requested()), authorization(), T1),
                T1, ignored -> true);
        Execution cancelled = service.cancel(runningAgain, T2, "authorized cancellation");
        assertEquals(Execution.State.CANCELLED, cancelled.state());
        assertTrue(cancelled.terminal());
        assertNotEquals(failed.state(), cancelled.state());
    }
}
