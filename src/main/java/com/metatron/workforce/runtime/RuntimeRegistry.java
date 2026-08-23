package com.metatron.workforce.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation-level registry for Worker Runtime Instances.
 *
 * This registry does not own Worker identity or Execution semantics.
 * It only tracks runtime-instance references required by implementation.
 */
public final class RuntimeRegistry {
    private final Map<String, RuntimeInstance> runtimes = new ConcurrentHashMap<>();

    public RuntimeInstance register(RuntimeInstance runtime) {
        runtimes.put(runtime.runtimeId(), runtime);
        return runtime;
    }

    public RuntimeInstance get(String runtimeId) {
        return runtimes.get(runtimeId);
    }

    public boolean remove(String runtimeId) {
        return runtimes.remove(runtimeId) != null;
    }

    public int size() {
        return runtimes.size();
    }
}
