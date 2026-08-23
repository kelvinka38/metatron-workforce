package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionPersistenceRecord(
        String executionId,
        String workerId,
        String assignmentId,
        String authorizationId,
        String state,
        Instant createdAt,
        Instant updatedAt
) {}
