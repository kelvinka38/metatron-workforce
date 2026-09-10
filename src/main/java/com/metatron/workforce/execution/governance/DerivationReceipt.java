package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.Objects;

/** Institution-issued proof that one WorkSpec was derived against a specific authority snapshot. */
public record DerivationReceipt(
        String receiptId,
        String objectiveId,
        String stepId,
        String workDigest,
        String authoritySnapshotId,
        String authorityDigest,
        String constraintBundleId,
        String constraintBundleDigest,
        Classification classification,
        Instant issuedAt) {

    public enum Classification { DERIVED, CHANGE_PROPOSAL_REQUIRED, REJECTED }

    public DerivationReceipt {
        receiptId = require(receiptId, "receiptId");
        objectiveId = require(objectiveId, "objectiveId");
        stepId = require(stepId, "stepId");
        workDigest = require(workDigest, "workDigest");
        authoritySnapshotId = require(authoritySnapshotId, "authoritySnapshotId");
        authorityDigest = require(authorityDigest, "authorityDigest");
        constraintBundleId = require(constraintBundleId, "constraintBundleId");
        constraintBundleDigest = require(constraintBundleDigest, "constraintBundleDigest");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }

    public boolean executableCandidate() { return classification == Classification.DERIVED; }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
