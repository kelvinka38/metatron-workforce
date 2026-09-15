package com.metatron.workforce.runtime;

import com.metatron.workforce.actor.WorkerActorRuntime;

import java.util.Objects;

/** Runtime-owned realization of Workforce execution-capacity requests. Worker identity is never replaced here. */
public final class RuntimeCapacityCoordinator {
    private final RuntimeRegistry registry;
    private final RuntimeLifecycleService lifecycle;
    private final WorkerActorRuntime actors;

    public RuntimeCapacityCoordinator(RuntimeRegistry registry) {
        this(registry, null);
    }

    public RuntimeCapacityCoordinator(RuntimeRegistry registry, WorkerActorRuntime actors) {
        this.registry = Objects.requireNonNull(registry);
        this.lifecycle = new RuntimeLifecycleService(registry);
        this.actors = actors;
    }

    public synchronized RuntimeInstance provision(String workerId) {
        String clean = require(workerId);
        if (actors != null) actors.ensureActor(clean);
        RuntimeInstance runtime = lifecycle.create(clean);
        return lifecycle.activate(runtime.runtimeId());
    }

    public synchronized RuntimeInstance ensureRunning(String workerId) {
        String clean = require(workerId);
        if (actors != null) actors.ensureActor(clean);
        return registry.runningForWorker(clean).orElseGet(() -> provision(clean));
    }

    public synchronized RuntimeInstance replace(String workerId, String failedRuntimeId) {
        String clean = require(workerId);
        RuntimeInstance old = registry.get(failedRuntimeId);
        if (old == null) throw new IllegalStateException("runtime not found: " + failedRuntimeId);
        if (!old.workerId().equals(clean)) throw new SecurityException("runtime worker attribution mismatch");
        if (old.state() != RuntimeState.FAILED && old.state() != RuntimeState.TERMINATED) lifecycle.fail(failedRuntimeId);
        RuntimeInstance replacement = provision(clean);
        if (!replacement.workerId().equals(old.workerId())) throw new IllegalStateException("runtime replacement changed Worker identity");
        return replacement;
    }

    public synchronized RuntimeInstance release(String workerId, String runtimeId) {
        String clean = require(workerId);
        RuntimeInstance runtime = registry.get(runtimeId);
        if (runtime == null) throw new IllegalStateException("runtime not found: " + runtimeId);
        if (!runtime.workerId().equals(clean)) throw new SecurityException("runtime worker attribution mismatch");
        if (runtime.state() == RuntimeState.TERMINATED) return runtime;
        return lifecycle.terminate(runtimeId);
    }

    public RuntimeInstance get(String runtimeId) { return registry.get(runtimeId); }

    /**
     * Root-cause fix (WORKFORCE RUNTIME RE-FOUNDATION, part 2 of 2): exposes the actor runtime so the
     * governed execution boundary can (a) filter worker selection by actual actor readiness and
     * (b) route capability effects through the actor's claim/complete lifecycle, not just cognition
     * calls. Returns null when this coordinator was constructed without an actor runtime (tests,
     * legacy single-arg constructor) -- callers must treat that as "no actor integration available"
     * and fall back to prior behavior, not fail.
     */
    public WorkerActorRuntime actors() { return actors; }

    private static String require(String workerId) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        return workerId.trim();
    }
}
