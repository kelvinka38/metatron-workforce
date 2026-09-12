package com.metatron.workforce.interaction.intelligence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable evidence for the capacity sub-lifecycle of one logical cognition request. */
public record CognitionCapacityEvent(
        String eventId,
        String eventType,
        String requestId,
        CognitionRequestState state,
        String workerId,
        String objectiveId,
        String assignmentId,
        String stepId,
        String executionAttemptId,
        String detail,
        Instant observedAt) {

    public CognitionCapacityEvent {
        eventId = require(eventId, "eventId");
        eventType = require(eventType, "eventType");
        requestId = require(requestId, "requestId");
        Objects.requireNonNull(state, "state");
        workerId = clean(workerId);
        objectiveId = clean(objectiveId);
        assignmentId = clean(assignmentId);
        stepId = clean(stepId);
        executionAttemptId = clean(executionAttemptId);
        detail = clean(detail);
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public static CognitionCapacityEvent transition(
            MetatronCognitionClient.Request request,
            CognitionRequestState state,
            String detail) {
        return event(request, eventType(state), state, detail);
    }

    public static CognitionCapacityEvent capacityRecovered(MetatronCognitionClient.Request request) {
        return event(request, "CognitionCapacityRecovered", CognitionRequestState.RUNNING,
                "queued_request_acquired_internal_capacity");
    }

    private static CognitionCapacityEvent event(
            MetatronCognitionClient.Request request,
            String eventType,
            CognitionRequestState state,
            String detail) {
        return new CognitionCapacityEvent(
                "cognition-event-" + UUID.randomUUID(),
                eventType,
                request.requestId(),
                state,
                request.origin().workerId(),
                request.origin().objectiveId(),
                request.origin().assignmentId(),
                request.origin().stepId(),
                request.origin().executionAttemptId(),
                detail,
                Instant.now());
    }

    public CognitionCapacityEvent reconciliationRequired() {
        return new CognitionCapacityEvent(
                "cognition-event-" + UUID.randomUUID(),
                "CognitionRequestReconciliationRequired",
                requestId,
                CognitionRequestState.RECONCILIATION_REQUIRED,
                workerId,
                objectiveId,
                assignmentId,
                stepId,
                executionAttemptId,
                "process_restart_requires_truthful_reconciliation",
                Instant.now());
    }

    private static String eventType(CognitionRequestState state) {
        return switch (state) {
            case ADMITTED -> "CognitionRequestAdmitted";
            case QUEUED -> "CognitionRequestQueued";
            case RUNNING -> "CognitionRequestStarted";
            case SUCCEEDED -> "CognitionRequestCompleted";
            case FAILED_RETRYABLE, FAILED_TERMINAL -> "CognitionRequestFailed";
            case RECONCILIATION_REQUIRED -> "CognitionRequestReconciliationRequired";
        };
    }

    private static String require(String value, String field) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return cleaned;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
