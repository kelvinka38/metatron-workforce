package com.metatron.workforce.execution;

import java.util.Objects;

public final class ExecutionCommandService {

    public ExecutionState execute(
            ExecutionCommand command,
            ExecutionState current
    ) {
        Objects.requireNonNull(command, "command");
        if (current != ExecutionState.ADMITTED) {
            throw new IllegalStateException("Execution command requires ADMITTED state");
        }
        return ExecutionState.RUNNING;
    }

    /** Execute a concrete, explicitly registered capability after admission. */
    public ExecutionResult dispatch(
            ExecutionCommand command,
            ExecutionState current,
            ExecutionCapabilityRegistry registry
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(registry, "registry");
        execute(command, current);
        return registry.require(command.action()).execute(command);
    }
}
