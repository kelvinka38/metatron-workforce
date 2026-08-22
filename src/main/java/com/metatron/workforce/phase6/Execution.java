package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;

/** Immutable institutional execution state with explicit lifecycle evidence. */
public record Execution(
        String executionId,
        String requestId,
        String workerId,
        String assignmentId,
        String authorizationId,
        State state,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        Instant terminalAt,
        String result,
        String failureReason,
        String evidenceReference) {

    public enum State { REQUESTED, VALIDATING, AUTHORIZED, RUNNING, COMPLETED, FAILED, BLOCKED, CANCELLED }

    public Execution {
        requireText(executionId, "executionId");
        requireText(requestId, "requestId");
        requireText(workerId, "workerId");
        requireText(assignmentId, "assignmentId");
        requireText(authorizationId, "authorizationId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedAt, "requestedAt");
        requireText(evidenceReference, "evidenceReference");
        if (startedAt != null && startedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("startedAt must not precede requestedAt");
        }
        if (completedAt != null && startedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
        if (terminalAt != null && terminalAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("terminalAt must not precede requestedAt");
        }
    }

    public static Execution requested(
            String executionId,
            String requestId,
            String workerId,
            String assignmentId,
            String authorizationId,
            Instant requestedAt,
            String evidenceReference) {
        return new Execution(executionId, requestId, workerId, assignmentId, authorizationId,
                State.REQUESTED, requestedAt, null, null, null, null, null, evidenceReference);
    }

    public boolean terminal() {
        return switch (state) {
            case COMPLETED, FAILED, CANCELLED -> true;
            default -> false;
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
