package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.Objects;

/** Governance identity attached to an existing ExecutionAttempt without changing its durable lease/fencing schema. */
public record ExecutionAttemptGovernanceBinding(
        String attemptId,
        long fencingToken,
        String objectiveId,
        String stepId,
        String authoritySnapshotId,
        String authorityDigest,
        String derivationReceiptId,
        String planId,
        int planVersion,
        String planDigest,
        Instant boundAt) {
    public ExecutionAttemptGovernanceBinding {
        attemptId = require(attemptId, "attemptId"); if (fencingToken < 1) throw new IllegalArgumentException("fencingToken must be positive");
        objectiveId = require(objectiveId, "objectiveId"); stepId = require(stepId, "stepId");
        authoritySnapshotId = require(authoritySnapshotId, "authoritySnapshotId"); authorityDigest = require(authorityDigest, "authorityDigest");
        derivationReceiptId = require(derivationReceiptId, "derivationReceiptId"); planId = require(planId, "planId");
        if (planVersion < 1) throw new IllegalArgumentException("planVersion must be positive");
        planDigest = require(planDigest, "planDigest"); Objects.requireNonNull(boundAt, "boundAt");
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field); String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank"); return normalized;
    }
}
