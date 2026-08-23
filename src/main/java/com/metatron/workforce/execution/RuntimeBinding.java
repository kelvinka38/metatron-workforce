package com.metatron.workforce.execution;

import com.metatron.workforce.runtime.RuntimeInstance;

/**
 * Binds an execution activity to a Worker Runtime Instance.
 *
 * Binding does not transfer authority from Execution to Runtime.
 */
public final class RuntimeBinding {
    private final String executionId;
    private final String runtimeId;

    public RuntimeBinding(ExecutionRecord execution, RuntimeInstance runtime) {
        this.executionId = execution.executionId();
        this.runtimeId = runtime.runtimeId();
    }

    public String executionId() {
        return executionId;
    }

    public String runtimeId() {
        return runtimeId;
    }
}
