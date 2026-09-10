package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Brain/Worker completion claim; not institutional completion authority. */
public record CompletionCandidate(
        String objectiveId,
        String stepId,
        String planId,
        int planVersion,
        String authoritySnapshotId,
        List<String> evidenceReferences,
        List<String> completedStepIds,
        List<String> unresolvedRequiredFailures,
        String sourceSha,
        String testedSha,
        String approvedSha,
        String deployedSha,
        String observedSha,
        Instant proposedAt) {
    public CompletionCandidate {
        objectiveId = require(objectiveId, "objectiveId");
        stepId = stepId == null ? "" : stepId.trim();
        planId = require(planId, "planId");
        if (planVersion < 1) throw new IllegalArgumentException("planVersion must be positive");
        authoritySnapshotId = require(authoritySnapshotId, "authoritySnapshotId");
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        completedStepIds = completedStepIds == null ? List.of() : List.copyOf(completedStepIds);
        unresolvedRequiredFailures = unresolvedRequiredFailures == null ? List.of() : List.copyOf(unresolvedRequiredFailures);
        sourceSha = clean(sourceSha); testedSha = clean(testedSha); approvedSha = clean(approvedSha);
        deployedSha = clean(deployedSha); observedSha = clean(observedSha);
        Objects.requireNonNull(proposedAt, "proposedAt");
    }

    public boolean carriesArtifactIdentity() {
        return !(sourceSha.isBlank() && testedSha.isBlank() && approvedSha.isBlank() && deployedSha.isBlank() && observedSha.isBlank());
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String require(String value, String field) {
        Objects.requireNonNull(value, field); String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank"); return normalized;
    }
}
