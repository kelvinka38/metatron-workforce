package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionTransitionRecord(
        String executionId,
        ExecutionState previous,
        ExecutionState next,
        Instant timestamp
) {}
