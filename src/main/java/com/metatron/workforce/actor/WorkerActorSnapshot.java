package com.metatron.workforce.actor;

import java.time.Instant;
import java.util.Objects;

/** Durable operational projection for one Worker actor. */
public record WorkerActorSnapshot(
        String actorId,
        String workerId,
        WorkerActorState state,
        long generation,
        Instant createdAt,
        Instant activatedAt,
        Instant heartbeatAt,
        Instant updatedAt,
        String currentObjectiveId,
        String currentAssignmentId,
        String currentStepId,
        String lastAction,
        String nextAction,
        int mailboxDepth) {

    public WorkerActorSnapshot {
        actorId = require(actorId, "actorId");
        workerId = require(workerId, "workerId");
        state = Objects.requireNonNull(state, "state");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        heartbeatAt = Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        currentObjectiveId = safe(currentObjectiveId);
        currentAssignmentId = safe(currentAssignmentId);
        currentStepId = safe(currentStepId);
        lastAction = safe(lastAction);
        nextAction = safe(nextAction);
        if (mailboxDepth < 0) throw new IllegalArgumentException("mailboxDepth must not be negative");
    }

    public static WorkerActorSnapshot create(String workerId, Instant at) {
        String clean = require(workerId, "workerId");
        return new WorkerActorSnapshot(
                "actor:" + clean,
                clean,
                WorkerActorState.IDLE,
                1,
                at,
                at,
                at,
                at,
                "",
                "",
                "",
                "actor materialized",
                "await work",
                0);
    }

    public WorkerActorSnapshot withState(
            WorkerActorState next,
            String objectiveId,
            String assignmentId,
            String stepId,
            String last,
            String nextAction,
            int depth,
            Instant at) {
        return new WorkerActorSnapshot(
                actorId, workerId, next, generation,
                createdAt, activatedAt, at, at,
                objectiveId, assignmentId, stepId,
                last, nextAction, depth);
    }

    public WorkerActorSnapshot heartbeat(int depth, Instant at) {
        return new WorkerActorSnapshot(
                actorId, workerId, state, generation,
                createdAt, activatedAt, at, at,
                currentObjectiveId, currentAssignmentId, currentStepId,
                lastAction, nextAction, depth);
    }

    public WorkerActorSnapshot withMailboxDepth(int depth) {
        return new WorkerActorSnapshot(
                actorId, workerId, state, generation,
                createdAt, activatedAt, heartbeatAt, updatedAt,
                currentObjectiveId, currentAssignmentId, currentStepId,
                lastAction, nextAction, depth);
    }

    public WorkerActorSnapshot recover(WorkerActorState next, int depth, Instant at) {
        return new WorkerActorSnapshot(
                actorId, workerId, next, generation + 1,
                createdAt, at, at, at,
                currentObjectiveId, currentAssignmentId, currentStepId,
                "actor recovered from durable state", "reconcile mailbox and assignments", depth);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
