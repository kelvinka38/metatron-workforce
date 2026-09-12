package com.metatron.workforce.interaction.intelligence;

import java.time.Instant;
import java.util.Objects;

/** Attribution/economic evidence for one physical or logical inference execution. */
public record InferenceConsumptionRecord(
        String requestId,
        IntelligenceOriginType originType,
        String actorId,
        String workerId,
        String objectiveId,
        String assignmentId,
        String stepId,
        String executionAttemptId,
        IntelligenceComputeOwner computeOwner,
        String endpointId,
        String modelIdentity,
        long inputTokens,
        long outputTokens,
        long latencyMillis,
        String status,
        String providerRequestReference,
        Instant observedAt) {

    public InferenceConsumptionRecord {
        requestId = require(requestId, "requestId");
        Objects.requireNonNull(originType, "originType");
        actorId = clean(actorId);
        workerId = clean(workerId);
        objectiveId = clean(objectiveId);
        assignmentId = clean(assignmentId);
        stepId = clean(stepId);
        executionAttemptId = clean(executionAttemptId);
        Objects.requireNonNull(computeOwner, "computeOwner");
        endpointId = require(endpointId, "endpointId");
        modelIdentity = clean(modelIdentity);
        status = require(status, "status");
        providerRequestReference = clean(providerRequestReference);
        Objects.requireNonNull(observedAt, "observedAt");
        if (inputTokens < 0 || outputTokens < 0 || latencyMillis < 0) {
            throw new IllegalArgumentException("inference usage values must be non-negative");
        }
    }

    public static InferenceConsumptionRecord from(
            IntelligenceOriginContext origin,
            IntelligenceComputeOwner computeOwner,
            String endpointId,
            String modelIdentity,
            long inputTokens,
            long outputTokens,
            long latencyMillis,
            String status,
            String providerRequestReference) {
        return new InferenceConsumptionRecord(
                origin.requestId(), origin.originType(), origin.actorId(), origin.workerId(), origin.objectiveId(),
                origin.assignmentId(), origin.stepId(), origin.executionAttemptId(), computeOwner,
                endpointId, modelIdentity, inputTokens, outputTokens, latencyMillis, status,
                providerRequestReference, Instant.now());
    }

    private static String require(String value, String field) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return cleaned;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
