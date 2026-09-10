package com.metatron.workforce.execution.governance;

import com.metatron.workforce.action.ActionFabric;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Trusted runtime envelope for one proposed effect; never constructed from raw model JSON directly. */
public record ExecutionIntent(
        String objectiveId,
        String attemptId,
        long fencingToken,
        String workerId,
        String assignmentRef,
        String authorizationRef,
        String planId,
        int planVersion,
        String stepId,
        String actionRef,
        ActionFabric.Consequence consequence,
        String targetScope,
        String authoritySnapshotId,
        String derivationReceiptId,
        Map<String, String> inputs,
        Instant requestedAt) {
    public ExecutionIntent {
        objectiveId = require(objectiveId, "objectiveId");
        attemptId = require(attemptId, "attemptId");
        if (fencingToken < 1) throw new IllegalArgumentException("fencingToken must be positive");
        workerId = require(workerId, "workerId");
        assignmentRef = require(assignmentRef, "assignmentRef");
        authorizationRef = require(authorizationRef, "authorizationRef");
        planId = require(planId, "planId");
        if (planVersion < 1) throw new IllegalArgumentException("planVersion must be positive");
        stepId = require(stepId, "stepId");
        actionRef = require(actionRef, "actionRef");
        Objects.requireNonNull(consequence, "consequence");
        targetScope = require(targetScope, "targetScope");
        authoritySnapshotId = require(authoritySnapshotId, "authoritySnapshotId");
        derivationReceiptId = require(derivationReceiptId, "derivationReceiptId");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
