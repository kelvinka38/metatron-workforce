package com.metatron.workforce.management;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable at-least-once dispatch envelope and effect outcome. */
public record DurableDispatch(
        String dispatchId,
        String objectiveId,
        int graphVersion,
        String stepId,
        String idempotencyKey,
        Status status,
        int attempt,
        List<String> evidenceReferences,
        String failure,
        Instant createdAt,
        Instant updatedAt) {
    public DurableDispatch {
        require(dispatchId, "dispatchId"); require(objectiveId, "objectiveId");
        require(stepId, "stepId"); require(idempotencyKey, "idempotencyKey");
        if (graphVersion < 1 || attempt < 1) throw new IllegalArgumentException("invalid dispatch version/attempt");
        Objects.requireNonNull(status, "status");
        evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        failure = failure == null ? "" : failure;
        Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
    }
    public enum Status { STARTED, SUCCEEDED, FAILED, DEAD_LETTERED }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
