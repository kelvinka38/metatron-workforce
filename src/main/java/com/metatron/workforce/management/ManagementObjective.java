package com.metatron.workforce.management;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Workforce-side management view of Objective ownership.
 *
 * This record intentionally stores Worker identity and institutional references only.
 * It does not own Execution, Authorization, Organization, or runtime semantics.
 */
public record ManagementObjective(
        String objectiveId,
        String ownerWorkerId,
        String organizationContextId,
        String description,
        Status status,
        List<String> assignmentRefs,
        List<String> evidenceRefs,
        Instant createdAt,
        Instant updatedAt) {

    public ManagementObjective {
        requireText(objectiveId, "objectiveId");
        requireText(ownerWorkerId, "ownerWorkerId");
        requireText(organizationContextId, "organizationContextId");
        requireText(description, "description");
        Objects.requireNonNull(status, "status");
        assignmentRefs = List.copyOf(Objects.requireNonNull(assignmentRefs, "assignmentRefs"));
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public enum Status {
        ACCEPTED,
        PLANNING,
        READY,
        EXECUTING,
        VERIFYING,
        COMPLETED,
        RECOVERING,
        REPLANNING,
        PAUSED,
        ACTIVE,
        BLOCKED,
        ESCALATED,
        DELIVERED,
        CANCELLED,
        SUPERSEDED,
        TRANSFERRED
    }

    public boolean terminal() {
        return status == Status.COMPLETED
                || status == Status.DELIVERED
                || status == Status.CANCELLED
                || status == Status.SUPERSEDED
                || status == Status.TRANSFERRED;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
