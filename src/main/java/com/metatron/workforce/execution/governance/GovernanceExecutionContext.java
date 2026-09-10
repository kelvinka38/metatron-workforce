package com.metatron.workforce.execution.governance;

import java.util.Objects;

/** Exact governance binding passed from the governed capability wrapper into the Cognitive Worker loop. */
public record GovernanceExecutionContext(
        String attemptId,
        long fencingToken,
        String planId,
        int planVersion,
        String authoritySnapshotId,
        String derivationReceiptId,
        String targetScope) {
    public GovernanceExecutionContext {
        attemptId = require(attemptId, "attemptId");
        if (fencingToken < 1) throw new IllegalArgumentException("fencingToken must be positive");
        planId = require(planId, "planId");
        if (planVersion < 1) throw new IllegalArgumentException("planVersion must be positive");
        authoritySnapshotId = require(authoritySnapshotId, "authoritySnapshotId");
        derivationReceiptId = require(derivationReceiptId, "derivationReceiptId");
        targetScope = require(targetScope, "targetScope");
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field); String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank"); return normalized;
    }
}
