package com.metatron.workforce.runtime.binding;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.RuntimeExecutionContext;
import com.metatron.workforce.runtime.RuntimeInstance;

import java.util.Objects;

public final class RuntimeExecutionBinder {

    public RuntimeExecutionContext bind(
            RuntimeInstance runtime,
            String executionId,
            String assignmentId,
            String authorizationId,
            ExecutionWorkSpec workSpec) {

        Objects.requireNonNull(runtime);
        Objects.requireNonNull(workSpec, "workSpec");

        if (runtime.state().name().equals("FAILED")
                || runtime.state().name().equals("TERMINATED")) {
            throw new IllegalStateException(
                    "runtime unavailable for execution");
        }

        return new RuntimeExecutionContext(
                runtime.runtimeId(),
                runtime.workerId(),
                executionId,
                assignmentId,
                authorizationId,
                workSpec
        );
    }
}
