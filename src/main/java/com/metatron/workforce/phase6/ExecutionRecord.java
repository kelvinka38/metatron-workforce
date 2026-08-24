package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExecutionRecord(
        String executionId,
        String proposalId,
        String actorId,
        String action,
        Instant startedAt,
        Instant completedAt,
        String contextId,
        String authorizationReference,
        List<String> inputs,
        List<String> outputs,
        Status status,
        String failureReason,
        String retryOfExecutionId) {
    public enum Status { ADMITTED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED, PARTIAL }

    public ExecutionRecord {
        require(executionId, "executionId"); require(proposalId, "proposalId"); require(actorId, "actorId");
        require(action, "action"); require(contextId, "contextId"); require(authorizationReference, "authorizationReference");
        Objects.requireNonNull(startedAt, "startedAt");
        if (completedAt != null && completedAt.isBefore(startedAt)) throw new IllegalArgumentException("completedAt must not precede startedAt");
        Objects.requireNonNull(inputs, "inputs"); Objects.requireNonNull(outputs, "outputs"); Objects.requireNonNull(status, "status");
        inputs = List.copyOf(inputs); outputs = List.copyOf(outputs);
        if ((status == Status.FAILED || status == Status.BLOCKED) && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException(status + " execution requires failureReason");
        }
        if (status == Status.SUCCEEDED && failureReason != null && !failureReason.isBlank()) throw new IllegalArgumentException("successful execution cannot have failureReason");
        if (retryOfExecutionId != null && retryOfExecutionId.isBlank()) throw new IllegalArgumentException("retryOfExecutionId must be blank or non-blank");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
