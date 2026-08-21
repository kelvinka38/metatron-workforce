package com.metatron.workforce.phase3;

import java.util.Objects;

/**
 * Technical binding between a validated execution handoff and a replaceable
 * worker runtime instance. Execution identity remains independent of runtime identity.
 */
public record RuntimeExecutionBinding(
        String executionId,
        String workerId,
        String assignmentId,
        String authorizationId,
        String runtimeId) {

    public RuntimeExecutionBinding {
        requireNonBlank(executionId, "executionId");
        requireNonBlank(workerId, "workerId");
        requireNonBlank(assignmentId, "assignmentId");
        requireNonBlank(authorizationId, "authorizationId");
        requireNonBlank(runtimeId, "runtimeId");
    }

    public static RuntimeExecutionBinding bind(
            ExecutionHandoffRequest handoff,
            WorkerRuntimeInstance runtime) {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(runtime, "runtime");

        if (!runtime.executable()) {
            throw new IllegalStateException("runtime is not executable: " + runtime.runtimeId());
        }
        if (!runtime.workerId().equals(handoff.workerId())) {
            throw new IllegalArgumentException("runtime worker does not match handoff worker");
        }

        return new RuntimeExecutionBinding(
                handoff.executionId(),
                handoff.workerId(),
                handoff.assignmentId(),
                handoff.authorizationId(),
                runtime.runtimeId());
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
