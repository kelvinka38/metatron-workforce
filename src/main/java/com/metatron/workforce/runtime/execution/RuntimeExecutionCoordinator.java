package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.RuntimeExecutionContext;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimeState;

import java.util.Objects;

public final class RuntimeExecutionCoordinator {

    public RuntimeExecutionContext start(
            RuntimeInstance runtime,
            String executionId,
            String assignmentId,
            String authorizationId,
            ExecutionWorkSpec workSpec) {

        Objects.requireNonNull(runtime);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(authorizationId);
        Objects.requireNonNull(workSpec, "workSpec");

        if (runtime.state() != RuntimeState.READY
                && runtime.state() != RuntimeState.RUNNING) {
            throw new IllegalStateException(
                    "runtime cannot execute: "
                    + runtime.state());
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
