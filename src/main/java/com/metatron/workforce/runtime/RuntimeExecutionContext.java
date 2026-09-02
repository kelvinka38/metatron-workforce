package com.metatron.workforce.runtime;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.Objects;

/** Bound runtime context for one admitted execution and its executable work. */
public record RuntimeExecutionContext(
        String runtimeId,
        String workerId,
        String executionId,
        String assignmentId,
        String authorizationId,
        ExecutionWorkSpec workSpec
) {

    public RuntimeExecutionContext {
        require(runtimeId);
        require(workerId);
        require(executionId);
        require(assignmentId);
        require(authorizationId);
        Objects.requireNonNull(workSpec, "workSpec");
    }

    private static void require(String value) {
        Objects.requireNonNull(value);

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "context value cannot be blank");
        }
    }
}
