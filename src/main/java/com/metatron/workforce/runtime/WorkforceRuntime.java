package com.metatron.workforce.runtime;

import com.metatron.workforce.runtime.binding.RuntimeExecutionBinder;
import com.metatron.workforce.runtime.RuntimeExecutionContext;

public final class WorkforceRuntime {

    private final RuntimeRegistry registry;
    private final RuntimeLifecycleService lifecycle;
    private final RuntimeDispatcher dispatcher;
    private final RuntimeFailureHandler failureHandler;
    private final RuntimeExecutionBinder binder;

    public WorkforceRuntime() {

        this.registry = new RuntimeRegistry();
        this.lifecycle =
                new RuntimeLifecycleService(registry);
        this.dispatcher =
                new RuntimeDispatcher(registry);
        this.failureHandler =
                new RuntimeFailureHandler(registry);
        this.binder =
                new RuntimeExecutionBinder();
    }

    public RuntimeInstance createWorkerRuntime(
            String workerId) {

        return lifecycle.create(workerId);
    }

    public RuntimeInstance startRuntime(
            String runtimeId) {

        RuntimeInstance runtime =
                registry.get(runtimeId);

        if (runtime.state() == RuntimeState.CREATED) {
            runtime.transition(RuntimeState.READY);
        }

        return dispatcher.dispatch(runtimeId);
    }

    public RuntimeExecutionContext bindExecution(
            RuntimeInstance runtime,
            String executionId,
            String assignmentId,
            String authorizationId) {

        return binder.bind(
                runtime,
                executionId,
                assignmentId,
                authorizationId
        );
    }

    public RuntimePersistenceRecord failRuntime(
            String runtimeId) {

        failureHandler.markFailed(runtimeId);

        return failureHandler.snapshot(runtimeId);
    }
}
