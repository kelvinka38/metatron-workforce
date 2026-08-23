package com.metatron.workforce.runtime;

import java.util.Objects;

public final class RuntimeDispatcher {

    private final RuntimeRegistry registry;

    public RuntimeDispatcher(RuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public RuntimeInstance dispatch(String runtimeId) {

        RuntimeInstance runtime = registry.get(runtimeId);

        if (runtime == null) {
            throw new IllegalStateException(
                    "runtime not found: " + runtimeId);
        }

        if (runtime.state() != RuntimeState.READY
                && runtime.state() != RuntimeState.RUNNING) {
            throw new IllegalStateException(
                    "runtime unavailable: " + runtime.state());
        }

        runtime.transition(RuntimeState.RUNNING);

        return runtime;
    }
}
