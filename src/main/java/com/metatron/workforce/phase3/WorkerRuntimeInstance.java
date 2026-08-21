package com.metatron.workforce.phase3;

import java.util.Objects;

/** Technical runtime-instance reference. Not a Worker identity or institutional domain. */
public record WorkerRuntimeInstance(String runtimeId, String workerId, RuntimeState state) {

    public WorkerRuntimeInstance {
        requireNonBlank(runtimeId, "runtimeId");
        requireNonBlank(workerId, "workerId");
        Objects.requireNonNull(state, "state");
    }

    public boolean executable() {
        return state == RuntimeState.READY || state == RuntimeState.ACTIVE;
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public enum RuntimeState { CREATED, READY, ACTIVE, TERMINATED, FAILED }
}
