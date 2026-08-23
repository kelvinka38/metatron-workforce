package com.metatron.workforce.runtime;

import java.util.Objects;

public record RuntimeExecutionCommand(
        String executionId,
        String workerId,
        String runtimeId) {

    public RuntimeExecutionCommand {
        requireText(executionId, "executionId");
        requireText(workerId, "workerId");
        requireText(runtimeId, "runtimeId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
