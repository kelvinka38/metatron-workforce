package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeLifecycleTest {

    @Test
    void runtimeLifecycleShouldPreserveWorkerIdentity() {

        RuntimeRegistry registry = new RuntimeRegistry();

        RuntimeLifecycleService lifecycle =
                new RuntimeLifecycleService(registry);

        RuntimeInstance runtime =
                lifecycle.create("worker-001");

        String runtimeId = runtime.runtimeId();

        assertEquals(
                "worker-001",
                runtime.workerId()
        );

        lifecycle.activate(runtimeId);

        assertEquals(
                RuntimeState.RUNNING,
                registry.get(runtimeId).state()
        );

        lifecycle.fail(runtimeId);

        assertEquals(
                RuntimeState.FAILED,
                registry.get(runtimeId).state()
        );

        assertEquals(
                "worker-001",
                registry.get(runtimeId).workerId()
        );
    }


    @Test
    void failedRuntimeShouldProducePersistenceRecord() {

        RuntimeRegistry registry = new RuntimeRegistry();

        RuntimeLifecycleService lifecycle =
                new RuntimeLifecycleService(registry);

        RuntimeFailureHandler handler =
                new RuntimeFailureHandler(registry);

        RuntimeInstance runtime =
                lifecycle.create("worker-002");

        handler.markFailed(runtime.runtimeId());

        RuntimePersistenceRecord record =
                handler.snapshot(runtime.runtimeId());

        assertEquals(
                runtime.runtimeId(),
                record.runtimeId()
        );

        assertEquals(
                "worker-002",
                record.workerId()
        );

        assertEquals(
                "FAILED",
                record.state()
        );
    }
}
