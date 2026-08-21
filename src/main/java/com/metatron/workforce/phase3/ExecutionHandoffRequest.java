package com.metatron.workforce.phase3;

import java.time.Instant;
import java.util.Objects;

/**
 * Workforce-owned implementation boundary object used to hand a resolved,
 * authorized assignment to Execution.
 *
 * This is not an institutional Runtime model and does not own execution
 * semantics. It preserves the correlation and authorization context required
 * for downstream execution realization.
 */
public record ExecutionHandoffRequest(
        String handoffId,
        String workerId,
        String assignmentId,
        String authorizationId,
        String workPackageId,
        String executionId,
        Instant issuedAt) {

    public ExecutionHandoffRequest {
        requireNonBlank(handoffId, "handoffId");
        requireNonBlank(workerId, "workerId");
        requireNonBlank(assignmentId, "assignmentId");
        requireNonBlank(authorizationId, "authorizationId");
        requireNonBlank(workPackageId, "workPackageId");
        requireNonBlank(executionId, "executionId");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
