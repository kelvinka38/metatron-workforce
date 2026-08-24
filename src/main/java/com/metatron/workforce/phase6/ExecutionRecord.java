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
        String failureReason) {
    public enum Status { SUCCEEDED, FAILED }

    public ExecutionRecord {
        require(executionId, "executionId"); require(proposalId, "proposalId"); require(actorId, "actorId");
        require(action, "action"); require(contextId, "contextId"); require(authorizationReference, "authorizationReference");
        Objects.requireNonNull(startedAt, "startedAt"); Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(inputs, "inputs"); Objects.requireNonNull(outputs, "outputs"); Objects.requireNonNull(status, "status");
        if (completedAt.isBefore(startedAt)) throw new IllegalArgumentException("completedAt must not precede startedAt");
        if (status == Status.FAILED && (failureReason == null || failureReason.isBlank())) throw new IllegalArgumentException("failed execution requires failureReason");
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
