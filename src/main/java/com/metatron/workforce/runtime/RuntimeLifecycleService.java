package com.metatron.workforce.runtime;

import java.util.Objects;

public final class RuntimeLifecycleService {
    private final RuntimeRegistry registry;
    public RuntimeLifecycleService(RuntimeRegistry registry) { this.registry = Objects.requireNonNull(registry); }

    public RuntimeInstance create(String workerId) {
        RuntimeInstance runtime = new RuntimeInstance(workerId);
        return registry.register(runtime);
    }
    public RuntimeInstance activate(String runtimeId) {
        RuntimeInstance runtime = find(runtimeId); runtime.transition(RuntimeState.RUNNING); return registry.register(runtime);
    }
    public RuntimeInstance fail(String runtimeId) {
        RuntimeInstance runtime = find(runtimeId); runtime.transition(RuntimeState.FAILED); return registry.register(runtime);
    }
    public RuntimeInstance terminate(String runtimeId) {
        RuntimeInstance runtime = find(runtimeId); runtime.transition(RuntimeState.TERMINATED); return registry.register(runtime);
    }
    private RuntimeInstance find(String runtimeId) {
        RuntimeInstance runtime = registry.get(runtimeId);
        if (runtime == null) throw new IllegalStateException("runtime not found: " + runtimeId);
        return runtime;
    }
}
