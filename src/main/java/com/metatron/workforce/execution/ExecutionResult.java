package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionResult(
        String executionId,
        ExecutionState state,
        String message,
        Instant completedAt
) {}
