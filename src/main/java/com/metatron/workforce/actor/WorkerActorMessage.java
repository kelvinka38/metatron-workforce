package com.metatron.workforce.actor;

import java.time.Instant;
import java.util.Objects;

/** Durable mailbox envelope owned by exactly one canonical Worker actor. */
public record WorkerActorMessage(
        String messageId,
        String workerId,
        Type type,
        Status status,
        String senderRef,
        String objectiveId,
        String assignmentId,
        String stepId,
        String capabilityRef,
        String payload,
        Instant createdAt,
        Instant claimedAt,
        Instant completedAt,
        String failure) {

    public enum Type { HUMAN_MESSAGE, ASSIGNMENT, DELEGATION, SYSTEM, RECOVERY }
    public enum Status { PENDING, CLAIMED, COMPLETED, FAILED, RECONCILIATION_REQUIRED }

    public WorkerActorMessage {
        messageId = require(messageId, "messageId");
        workerId = require(workerId, "workerId");
        type = Objects.requireNonNull(type, "type");
        status = Objects.requireNonNull(status, "status");
        senderRef = safe(senderRef);
        objectiveId = safe(objectiveId);
        assignmentId = safe(assignmentId);
        stepId = safe(stepId);
        capabilityRef = safe(capabilityRef);
        payload = safe(payload);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        failure = safe(failure);
    }

    public WorkerActorMessage claim(Instant at) {
        if (status != Status.PENDING) throw new IllegalStateException("only pending mailbox message can be claimed");
        return new WorkerActorMessage(messageId, workerId, type, Status.CLAIMED, senderRef,
                objectiveId, assignmentId, stepId, capabilityRef, payload,
                createdAt, Objects.requireNonNull(at), null, "");
    }

    public WorkerActorMessage complete(Instant at) {
        return new WorkerActorMessage(messageId, workerId, type, Status.COMPLETED, senderRef,
                objectiveId, assignmentId, stepId, capabilityRef, payload,
                createdAt, claimedAt, Objects.requireNonNull(at), "");
    }

    public WorkerActorMessage fail(String reason, Instant at) {
        return new WorkerActorMessage(messageId, workerId, type, Status.FAILED, senderRef,
                objectiveId, assignmentId, stepId, capabilityRef, payload,
                createdAt, claimedAt, Objects.requireNonNull(at), safe(reason));
    }

    public WorkerActorMessage reconciliationRequired(String reason, Instant at) {
        return new WorkerActorMessage(messageId, workerId, type, Status.RECONCILIATION_REQUIRED, senderRef,
                objectiveId, assignmentId, stepId, capabilityRef, payload,
                createdAt, claimedAt, Objects.requireNonNull(at), safe(reason));
    }

    public boolean terminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.RECONCILIATION_REQUIRED;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
