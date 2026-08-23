package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeContinuityContractTest {

    @Test
    void runtimeFailureMustNotChangeWorkerIdentityConcept() {
        RuntimeInstance runtimeA = new RuntimeInstance("worker-001");
        String workerId = runtimeA.workerId();

        runtimeA.transition(RuntimeState.FAILED);

        RuntimeInstance runtimeB = new RuntimeInstance(workerId);

        assertEquals(workerId, runtimeB.workerId());
        assertNotEquals(runtimeA.runtimeId(), runtimeB.runtimeId());
    }
}
