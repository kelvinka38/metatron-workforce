package com.metatron.workforce.execution;

public final class ExecutionCommandService {

    public ExecutionState execute(
            ExecutionCommand command,
            ExecutionState current
    ) {

        if (current != ExecutionState.ADMITTED) {
            throw new IllegalStateException(
                    "Execution command requires ADMITTED state"
            );
        }

        return ExecutionState.RUNNING;
    }
}
