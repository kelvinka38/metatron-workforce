package com.metatron.workforce.interaction.intelligence;

import java.util.Objects;

/** Typed institutional provenance for one Intelligence invocation. */
public record IntelligenceOriginContext(
        IntelligenceOriginType originType,
        String actorId,
        String workerId,
        String objectiveId,
        String assignmentId,
        String stepId,
        String executionAttemptId,
        String capabilityRef,
        String requestId) {

    public IntelligenceOriginContext {
        Objects.requireNonNull(originType, "originType");
        actorId = clean(actorId);
        workerId = clean(workerId);
        objectiveId = clean(objectiveId);
        assignmentId = clean(assignmentId);
        stepId = clean(stepId);
        executionAttemptId = clean(executionAttemptId);
        capabilityRef = clean(capabilityRef);
        requestId = clean(requestId);
        if (requestId.isBlank()) throw new IllegalArgumentException("requestId must not be blank");
        if (originType == IntelligenceOriginType.WORKER && workerId.isBlank()) {
            throw new IllegalArgumentException("WORKER origin requires workerId");
        }
        if (originType == IntelligenceOriginType.WORKER && actorId.isBlank()) actorId = workerId;
    }

    public static IntelligenceOriginContext human(String actorId, String requestId, String capabilityRef) {
        return new IntelligenceOriginContext(
                IntelligenceOriginType.HUMAN, actorId, "", "", "", "", "", capabilityRef, requestId);
    }

    public static IntelligenceOriginContext worker(
            String workerId,
            String objectiveId,
            String assignmentId,
            String stepId,
            String executionAttemptId,
            String capabilityRef,
            String requestId) {
        return new IntelligenceOriginContext(
                IntelligenceOriginType.WORKER, workerId, workerId, objectiveId, assignmentId,
                stepId, executionAttemptId, capabilityRef, requestId);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
