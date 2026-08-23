package com.metatron.workforce.execution;

import java.time.Instant;

public final class ExecutionResultService {

    public ExecutionResult complete(
            String executionId,
            String message
    ) {
        return new ExecutionResult(
                executionId,
                ExecutionState.COMPLETED,
                message,
                Instant.now()
        );
    }
}
