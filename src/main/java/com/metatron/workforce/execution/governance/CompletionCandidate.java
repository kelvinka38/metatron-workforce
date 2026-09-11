package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        Map<String, String> acceptanceSatisfaction,
        Map<String, String> evidenceSatisfaction,
        boolean observationPassed,
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
        acceptanceSatisfaction = acceptanceSatisfaction == null ? Map.of() : Map.copyOf(acceptanceSatisfaction);
        evidenceSatisfaction = evidenceSatisfaction == null ? Map.of() : Map.copyOf(evidenceSatisfaction);
        sourceSha = clean(sourceSha); testedSha = clean(testedSha); approvedSha = clean(approvedSha);
        deployedSha = clean(deployedSha); observedSha = clean(observedSha);
        Objects.requireNonNull(proposedAt, "proposedAt");
    }

    /**
     * Source SHA alone is provenance for proposal-producing work and does not mean a released artifact exists.
     * Once any downstream artifact identity is asserted, CompletionGate requires the full exact-SHA release chain.
     */
    public boolean carriesArtifactIdentity() {
        return !(testedSha.isBlank() && approvedSha.isBlank() && deployedSha.isBlank() && observedSha.isBlank());
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String require(String value, String field) {
        Objects.requireNonNull(value, field); String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank"); return normalized;
    }
}
