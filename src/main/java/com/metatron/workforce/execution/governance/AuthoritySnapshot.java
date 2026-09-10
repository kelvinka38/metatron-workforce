package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable authority set bound to one target/scope. */
public record AuthoritySnapshot(
        String snapshotId,
        String objectiveId,
        String targetEntity,
        String targetScope,
        List<AuthorityArtifact> artifacts,
        List<String> constraintIds,
        ConflictStatus conflictStatus,
        String digest,
        Instant createdAt) {

    public enum ConflictStatus { NONE, CONFLICT, UNRESOLVED }

    public AuthoritySnapshot {
        snapshotId = require(snapshotId, "snapshotId");
        objectiveId = require(objectiveId, "objectiveId");
        targetEntity = require(targetEntity, "targetEntity");
        targetScope = require(targetScope, "targetScope");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        constraintIds = constraintIds == null ? List.of() : constraintIds.stream().filter(Objects::nonNull)
                .map(String::trim).filter(v -> !v.isBlank()).distinct().sorted().toList();
        Objects.requireNonNull(conflictStatus, "conflictStatus");
        digest = require(digest, "digest");
        Objects.requireNonNull(createdAt, "createdAt");
        if (artifacts.isEmpty()) throw new IllegalArgumentException("authority artifacts required");
    }

    public static AuthoritySnapshot create(String objectiveId, String targetEntity, String targetScope,
                                           List<AuthorityArtifact> artifacts, List<String> constraintIds,
                                           ConflictStatus conflictStatus, Instant at) {
        List<AuthorityArtifact> ordered = artifacts.stream()
                .sorted(Comparator.comparing(AuthorityArtifact::identity)).toList();
        List<String> constraints = constraintIds == null ? List.of() : constraintIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).distinct().sorted().toList();
        String material = targetEntity.trim() + "\n" + targetScope.trim() + "\n"
                + ordered.stream().map(AuthorityArtifact::identity).reduce("", (a, b) -> a + b + "\n")
                + String.join("\n", constraints) + "\n" + conflictStatus.name();
        String digest = GovernanceDigests.sha256(material);
        return new AuthoritySnapshot("authority:" + digest, objectiveId, targetEntity, targetScope,
                ordered, constraints, conflictStatus, digest, at);
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
