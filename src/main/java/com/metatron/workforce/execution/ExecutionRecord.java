package com.metatron.workforce.execution;

import java.time.Instant;

/**
 * Execution identity anchor.
 * Execution semantics remain owned by the Execution capability contract.
 */
public final class ExecutionRecord {
    private final String executionId;
    private final String assignmentId;
    private final String workerId;
    private final Instant createdAt;

    public ExecutionRecord(String executionId, String assignmentId, String workerId) {
        this.executionId = executionId;
        this.assignmentId = assignmentId;
        this.workerId = workerId;
        this.createdAt = Instant.now();
    }

    public String executionId() { return executionId; }
    public String assignmentId() { return assignmentId; }
    public String workerId() { return workerId; }
    public Instant createdAt() { return createdAt; }
}
