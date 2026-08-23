package com.metatron.workforce.runtime;

import java.time.Instant;
import java.util.Objects;

public final class RuntimeFailureHandler {

    private final RuntimeRegistry registry;

    public RuntimeFailureHandler(RuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public RuntimeInstance markFailed(String runtimeId) {

        RuntimeInstance runtime =
                registry.get(runtimeId);

        if (runtime == null) {
            throw new IllegalStateException(
                    "runtime not found: " + runtimeId);
        }

        runtime.transition(RuntimeState.FAILED);

        registry.register(runtime);

        return runtime;
    }


    public RuntimePersistenceRecord snapshot(String runtimeId) {

        RuntimeInstance runtime =
                registry.get(runtimeId);

        if (runtime == null) {
            throw new IllegalStateException(
                    "runtime not found: " + runtimeId);
        }

        return new RuntimePersistenceRecord(
                runtime.runtimeId(),
                runtime.workerId(),
                runtime.state().name(),
                Instant.now()
        );
    }


    // implementation aliases

    public RuntimeInstance fail(String runtimeId) {
        return markFailed(runtimeId);
    }

    public RuntimePersistenceRecord captureFailureState(String runtimeId) {
        return snapshot(runtimeId);
    }
}
