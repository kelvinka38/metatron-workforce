package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable Workforce-owned execution-management context for one accepted Objective. */
public record AutonomousObjectiveWork(
        String objectiveId,
        String humanId,
        String organizationContextId,
        String caseId,
        String conversationId,
        String externalMessageReference,
        String channel,
        NormalizedRequest normalizedRequest,
        List<ExecutionWorkSpec> plannedWork,
        List<String> completedStepIds,
        List<String> evidenceReferences,
        Status status,
        String blocker,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public AutonomousObjectiveWork {
        requireText(objectiveId, "objectiveId");
        requireText(humanId, "humanId");
        requireText(organizationContextId, "organizationContextId");
        requireText(caseId, "caseId");
        requireText(conversationId, "conversationId");
        requireText(externalMessageReference, "externalMessageReference");
        requireText(channel, "channel");
        Objects.requireNonNull(normalizedRequest, "normalizedRequest");
        plannedWork = List.copyOf(Objects.requireNonNull(plannedWork, "plannedWork"));
        completedStepIds = List.copyOf(Objects.requireNonNull(completedStepIds, "completedStepIds"));
        evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
        Objects.requireNonNull(status, "status");
        blocker = blocker == null ? "" : blocker;
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public enum Status {
        PENDING_PLANNING,
        PLANNING,
        READY,
        EXECUTING,
        BLOCKED,
        COMPLETED,
        CANCELLED
    }

    public boolean terminal() {
        return status == Status.COMPLETED || status == Status.CANCELLED;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
