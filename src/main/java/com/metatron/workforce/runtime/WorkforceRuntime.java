package com.metatron.workforce.runtime;

import com.metatron.workforce.runtime.binding.RuntimeExecutionBinder;

import java.nio.file.Path;
import java.util.Objects;

public final class WorkforceRuntime {

    private final RuntimeRegistry registry;
    private final RuntimeLifecycleService lifecycle;
    private final RuntimeDispatcher dispatcher;
    private final RuntimeFailureHandler failureHandler;
    private final RuntimeExecutionBinder binder;

    /** Test-compatible runtime using process-local persistence. */
    public WorkforceRuntime() {
        this(new InMemoryRuntimePersistenceStore());
    }

    /** Deployable runtime with explicit durable runtime-state storage. */
    public WorkforceRuntime(Path persistenceRoot) {
        this(new FileRuntimePersistenceStore(persistenceRoot));
    }

    public WorkforceRuntime(RuntimePersistenceStore persistence) {
        Objects.requireNonNull(persistence);
        this.registry = new RuntimeRegistry(persistence);
        this.lifecycle = new RuntimeLifecycleService(registry);
        this.dispatcher = new RuntimeDispatcher(registry);
        this.failureHandler = new RuntimeFailureHandler(registry);
        this.binder = new RuntimeExecutionBinder();
    }

    public RuntimeInstance createWorkerRuntime(String workerId) {
        return lifecycle.create(workerId);
    }

    public RuntimeInstance startRuntime(String runtimeId) {
        RuntimeInstance runtime = registry.get(runtimeId);
        if (runtime == null) {
            throw new IllegalStateException("runtime not found: " + runtimeId);
        }

        if (runtime.state() == RuntimeState.CREATED) {
            runtime.transition(RuntimeState.READY);
            registry.register(runtime);
        }

        return dispatcher.dispatch(runtimeId);
    }

    public RuntimeExecutionContext bindExecution(
            RuntimeInstance runtime,
            String executionId,
            String assignmentId,
            String authorizationId) {
        return binder.bind(runtime, executionId, assignmentId, authorizationId);
    }

    public RuntimePersistenceRecord failRuntime(String runtimeId) {
        failureHandler.markFailed(runtimeId);
        return failureHandler.snapshot(runtimeId);
    }

    /** Restore a runtime instance from the durable store after JVM replacement. */
    public RuntimeInstance recoverRuntime(String runtimeId) {
        RuntimeInstance recovered = registry.get(runtimeId);
        if (recovered == null) {
            throw new IllegalStateException("runtime not found for recovery: " + runtimeId);
        }
        return recovered;
    }
}
