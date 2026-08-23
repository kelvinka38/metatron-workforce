package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionCommand(
        String executionId,
        String action,
        Instant issuedAt
) {}
