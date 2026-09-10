package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.Objects;

/** Short-lived non-transferable proof that one exact effect passed the institutional execution gate. */
public record ExecutionPermit(
        String permitId,
        String objectiveId,
        String attemptId,
        long fencingToken,
        String workerId,
        String assignmentRef,
        String authorizationRef,
        String planId,
        int planVersion,
        String planDigest,
        String stepId,
        String actionRef,
        String authorityDigest,
        String targetScope,
        Instant issuedAt,
        Instant expiresAt) {
    public ExecutionPermit {
        permitId = require(permitId, "permitId"); objectiveId = require(objectiveId, "objectiveId");
        attemptId = require(attemptId, "attemptId"); if (fencingToken < 1) throw new IllegalArgumentException("fencingToken must be positive");
        workerId = require(workerId, "workerId"); assignmentRef = require(assignmentRef, "assignmentRef");
        authorizationRef = require(authorizationRef, "authorizationRef"); planId = require(planId, "planId");
        if (planVersion < 1) throw new IllegalArgumentException("planVersion must be positive");
        planDigest = require(planDigest, "planDigest"); stepId = require(stepId, "stepId");
        actionRef = require(actionRef, "actionRef"); authorityDigest = require(authorityDigest, "authorityDigest");
        targetScope = require(targetScope, "targetScope"); Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("permit expiry must be after issuance");
    }

    public boolean validAt(Instant at) { return !at.isBefore(issuedAt) && at.isBefore(expiresAt); }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field); String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank"); return normalized;
    }
}
