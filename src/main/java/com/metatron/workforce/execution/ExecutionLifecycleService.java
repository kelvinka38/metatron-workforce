package com.metatron.workforce.execution;

import java.time.Instant;

public final class ExecutionLifecycleService {

    private final ExecutionTransitionGuard guard =
            new ExecutionTransitionGuard();


    public ExecutionTransitionRecord transition(
            String executionId,
            ExecutionState current,
            ExecutionState next
    ) {

        if (!guard.allowed(current,next)) {

            throw new InvalidExecutionTransitionException(
                    "Invalid transition "
                    + current
                    + " -> "
                    + next
            );
        }


        return new ExecutionTransitionRecord(
                executionId,
                current,
                next,
                Instant.now()
        );
    }
}
