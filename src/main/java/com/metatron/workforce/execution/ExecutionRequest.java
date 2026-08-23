package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionRequest(
        String executionId,
        Assignment assignment,
        Authorization authorization,
        Instant createdAt
) {}
