package com.metatron.workforce.runtime;

import java.util.Objects;

public record RuntimeExecutionContext(
        String runtimeId,
        String workerId,
        String executionId,
        String assignmentId,
        String authorizationId
) {

    public RuntimeExecutionContext {
        require(runtimeId);
        require(workerId);
        require(executionId);
        require(assignmentId);
        require(authorizationId);
    }

    private static void require(String value) {
        Objects.requireNonNull(value);

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "context value cannot be blank");
        }
    }
}
