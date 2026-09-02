package com.metatron.workforce.runtime;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.Objects;

/**
 * Runtime execution envelope.
 *
 * Attribution identifies the execution, worker and replaceable runtime embodiment; workSpec carries
 * the actual executable Workforce work. A runtime command cannot exist without both.
 */
public record RuntimeExecutionCommand(
        String executionId,
        String workerId,
        String runtimeId,
        ExecutionWorkSpec workSpec) {

    public RuntimeExecutionCommand {
        requireText(executionId, "executionId");
        requireText(workerId, "workerId");
        requireText(runtimeId, "runtimeId");
        Objects.requireNonNull(workSpec, "workSpec");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
