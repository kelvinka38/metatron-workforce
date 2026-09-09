package com.metatron.workforce.runtime;

import java.util.Objects;

/** Runtime-owned realization of Workforce execution-capacity requests. Worker identity is never replaced here. */
public final class RuntimeCapacityCoordinator {
    private final RuntimeRegistry registry;
    private final RuntimeLifecycleService lifecycle;

    public RuntimeCapacityCoordinator(RuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        this.lifecycle = new RuntimeLifecycleService(registry);
    }

    public synchronized RuntimeInstance provision(String workerId) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        RuntimeInstance runtime = lifecycle.create(workerId.trim());
        return lifecycle.activate(runtime.runtimeId());
    }

    public synchronized RuntimeInstance ensureRunning(String workerId) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        return registry.runningForWorker(workerId.trim()).orElseGet(() -> provision(workerId.trim()));
    }

    public synchronized RuntimeInstance replace(String workerId, String failedRuntimeId) {
        RuntimeInstance old = registry.get(failedRuntimeId);
        if (old == null) throw new IllegalStateException("runtime not found: " + failedRuntimeId);
        if (!old.workerId().equals(workerId)) throw new SecurityException("runtime worker attribution mismatch");
        if (old.state() != RuntimeState.FAILED && old.state() != RuntimeState.TERMINATED) lifecycle.fail(failedRuntimeId);
        RuntimeInstance replacement = provision(workerId);
        if (!replacement.workerId().equals(old.workerId())) throw new IllegalStateException("runtime replacement changed Worker identity");
        return replacement;
    }

    public synchronized RuntimeInstance release(String workerId, String runtimeId) {
        RuntimeInstance runtime = registry.get(runtimeId);
        if (runtime == null) throw new IllegalStateException("runtime not found: " + runtimeId);
        if (!runtime.workerId().equals(workerId)) throw new SecurityException("runtime worker attribution mismatch");
        if (runtime.state() == RuntimeState.TERMINATED) return runtime;
        return lifecycle.terminate(runtimeId);
    }

    public RuntimeInstance get(String runtimeId) { return registry.get(runtimeId); }
}
