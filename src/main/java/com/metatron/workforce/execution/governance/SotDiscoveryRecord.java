package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Auditable result of the mandatory SoT discovery gate. */
public record SotDiscoveryRecord(
        String discoveryId,
        String objectiveId,
        String actorId,
        String taskType,
        String targetEntity,
        String targetScope,
        List<String> authorityArtifactIdentities,
        List<String> relevantConstraintIds,
        List<String> evidenceSources,
        AuthoritySnapshot.ConflictStatus conflictStatus,
        boolean gatePassed,
        Instant discoveredAt) {
    public SotDiscoveryRecord {
        discoveryId = require(discoveryId, "discoveryId");
        objectiveId = require(objectiveId, "objectiveId");
        actorId = require(actorId, "actorId");
        taskType = require(taskType, "taskType");
        targetEntity = require(targetEntity, "targetEntity");
        targetScope = require(targetScope, "targetScope");
        authorityArtifactIdentities = authorityArtifactIdentities == null ? List.of() : List.copyOf(authorityArtifactIdentities);
        relevantConstraintIds = relevantConstraintIds == null ? List.of() : List.copyOf(relevantConstraintIds);
        evidenceSources = evidenceSources == null ? List.of() : List.copyOf(evidenceSources);
        Objects.requireNonNull(conflictStatus, "conflictStatus");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        if (gatePassed && (authorityArtifactIdentities.isEmpty() || conflictStatus != AuthoritySnapshot.ConflictStatus.NONE)) {
            throw new IllegalArgumentException("passing discovery requires authority and no conflict");
        }
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
