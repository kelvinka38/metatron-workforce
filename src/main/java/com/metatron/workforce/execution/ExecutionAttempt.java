package com.metatron.workforce.execution;

import java.time.Instant;
import java.util.Objects;

/** Execution-owned attempt identity. Worker/Assignment identity remains external references. */
public record ExecutionAttempt(String attemptId, String dispatchId, String objectiveId, String stepId,
                               String workerId, String assignmentRef, String authorizationRef,
                               String runtimeId, int attemptNumber, long fencingToken, Status status,
                               Instant leaseExpiresAt, Instant heartbeatAt, String checkpointRef,
                               String failure, Instant createdAt, Instant updatedAt) {
    public enum Status { LEASED, RUNNING, SUCCEEDED, FAILED, ABANDONED, FENCED }
    public ExecutionAttempt {
        require(attemptId,"attemptId"); require(dispatchId,"dispatchId"); require(objectiveId,"objectiveId");
        require(stepId,"stepId"); require(workerId,"workerId"); require(assignmentRef,"assignmentRef");
        require(authorizationRef,"authorizationRef"); require(runtimeId,"runtimeId");
        if (attemptNumber < 1 || fencingToken < 1) throw new IllegalArgumentException("attempt/fencing token must be positive");
        Objects.requireNonNull(status); Objects.requireNonNull(leaseExpiresAt); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        checkpointRef = checkpointRef == null ? "" : checkpointRef;
        failure = failure == null ? "" : failure;
    }
    public boolean terminal() { return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.ABANDONED || status == Status.FENCED; }
    private static void require(String v,String f){ if(v==null||v.isBlank()) throw new IllegalArgumentException(f+" required"); }
}
