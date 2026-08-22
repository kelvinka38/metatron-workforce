package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Predicate;

/** Controls execution admission and terminal transitions while preserving evidence. */
public final class ExecutionService {

    public Execution validating(Execution execution) {
        requireState(execution, Execution.State.REQUESTED);
        return copy(execution, Execution.State.VALIDATING, null, null, null, null, null);
    }

    public Execution admit(Execution execution, AuthorizationDecision authorization, Instant at) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(at, "at");
        requireState(execution, Execution.State.VALIDATING);
        if (!authorization.usableAt(at) || !authorization.authorizationId().equals(execution.authorizationId())) {
            throw new IllegalStateException("execution cannot be admitted without a valid matching authorization");
        }
        return copy(execution, Execution.State.AUTHORIZED, null, null, null, null, null);
    }

    public Execution start(
            Execution execution,
            Instant at,
            Predicate<Execution> feasibilityCheck) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(feasibilityCheck, "feasibilityCheck");
        requireState(execution, Execution.State.AUTHORIZED);
        if (!feasibilityCheck.test(execution)) {
            return copy(execution, Execution.State.BLOCKED, null, null, at,
                    "execution admission constraints are insufficient", null);
        }
        return copy(execution, Execution.State.RUNNING, at, null, null, null, null);
    }

    public Execution complete(Execution execution, Instant at, String result) {
        Objects.requireNonNull(at, "at");
        requireText(result, "result");
        requireState(execution, Execution.State.RUNNING);
        return copy(execution, Execution.State.COMPLETED, null, at, at, result, null);
    }

    public Execution fail(Execution execution, Instant at, String reason) {
        Objects.requireNonNull(at, "at");
        requireText(reason, "reason");
        requireState(execution, Execution.State.RUNNING);
        return copy(execution, Execution.State.FAILED, null, null, at, null, reason);
    }

    public Execution block(Execution execution, Instant at, String reason) {
        Objects.requireNonNull(at, "at");
        requireText(reason, "reason");
        if (execution.state() != Execution.State.RUNNING && execution.state() != Execution.State.AUTHORIZED) {
            throw new IllegalStateException("only authorized or running execution can become blocked");
        }
        return copy(execution, Execution.State.BLOCKED, null, null, at, null, reason);
    }

    public Execution cancel(Execution execution, Instant at, String reason) {
        Objects.requireNonNull(at, "at");
        requireText(reason, "reason");
        if (execution.terminal()) {
            throw new IllegalStateException("terminal execution cannot be cancelled");
        }
        return copy(execution, Execution.State.CANCELLED, null, null, at, null, reason);
    }

    private static Execution copy(
            Execution e,
            Execution.State state,
            Instant startedAt,
            Instant completedAt,
            Instant terminalAt,
            String result,
            String failureReason) {
        return new Execution(
                e.executionId(), e.requestId(), e.workerId(), e.assignmentId(), e.authorizationId(),
                state, e.requestedAt(),
                startedAt != null ? startedAt : e.startedAt(),
                completedAt != null ? completedAt : e.completedAt(),
                terminalAt != null ? terminalAt : e.terminalAt(),
                result != null ? result : e.result(),
                failureReason != null ? failureReason : e.failureReason(),
                e.evidenceReference());
    }

    private static void requireState(Execution execution, Execution.State expected) {
        Objects.requireNonNull(execution, "execution");
        if (execution.state() != expected) {
            throw new IllegalStateException("expected state " + expected + " but was " + execution.state());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
