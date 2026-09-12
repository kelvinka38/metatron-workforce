package com.metatron.workforce.runtime.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable binding between the canonical ExecutionAttempt and its mutable execution workspace. */
public record ExecutionWorkspaceBinding(
        String workspaceId,
        String attemptId,
        long attemptFencingToken,
        String workerId,
        String objectiveId,
        String stepId,
        String rootPath,
        long stateVersion,
        Status status,
        List<ExecutionRepositoryComponent> repositories,
        Instant createdAt,
        Instant updatedAt,
        Instant sealedAt,
        Instant retentionUntil) {

    public enum Status { ALLOCATED, MATERIALIZING, READY, SEALED, RECOVERING, RETAINED, DISPOSED }

    public ExecutionWorkspaceBinding {
        workspaceId = require(workspaceId, "workspaceId");
        attemptId = require(attemptId, "attemptId");
        if (attemptFencingToken < 1) throw new IllegalArgumentException("attemptFencingToken must be positive");
        workerId = require(workerId, "workerId");
        objectiveId = require(objectiveId, "objectiveId");
        stepId = require(stepId, "stepId");
        rootPath = require(rootPath, "rootPath");
        if (stateVersion < 1) throw new IllegalArgumentException("stateVersion must be positive");
        Objects.requireNonNull(status, "status");
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean mutable() {
        return status == Status.ALLOCATED || status == Status.MATERIALIZING || status == Status.READY || status == Status.RECOVERING;
    }

    public ExecutionRepositoryComponent requireComponent(String componentId) {
        return repositories.stream().filter(c -> c.componentId().equals(componentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("repository component not found: " + componentId));
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
