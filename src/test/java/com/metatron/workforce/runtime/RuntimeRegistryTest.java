package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeRegistryTest {

    @Test
    void runtimeRegistryResolvesRuntimeByRuntimeId() {
        RuntimeRegistry registry = new RuntimeRegistry();
        RuntimeInstance runtime = new RuntimeInstance("worker-001");

        registry.register(runtime);

        assertEquals(runtime, registry.get(runtime.runtimeId()));
        assertEquals("worker-001", registry.get(runtime.runtimeId()).workerId());
    }

    @Test
    void runtimeRemovalDoesNotChangeWorkerIdentityReference() {
        RuntimeRegistry registry = new RuntimeRegistry();
        RuntimeInstance runtime = new RuntimeInstance("worker-001");
        String workerId = runtime.workerId();
        String runtimeId = runtime.runtimeId();

        registry.register(runtime);
        assertTrue(registry.remove(runtimeId));

        assertNull(registry.get(runtimeId));
        assertEquals("worker-001", workerId);
    }

    @Test
    void runtimeIdIsDistinctFromWorkerId() {
        RuntimeInstance runtime = new RuntimeInstance("worker-001");

        assertNotEquals(runtime.workerId(), runtime.runtimeId());
    }
}
