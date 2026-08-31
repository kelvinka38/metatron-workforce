package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCapacityCoordinatorTest {
    @Test void runtimeReplacementPreservesInstitutionalWorkerIdentity(){
        RuntimeRegistry registry=new RuntimeRegistry(); RuntimeCapacityCoordinator capacity=new RuntimeCapacityCoordinator(registry);
        RuntimeInstance first=capacity.provision("worker:stable");
        assertEquals(RuntimeState.RUNNING,first.state());
        RuntimeInstance replacement=capacity.replace("worker:stable",first.runtimeId());
        assertNotEquals(first.runtimeId(),replacement.runtimeId());
        assertEquals("worker:stable",replacement.workerId());
        assertEquals(RuntimeState.FAILED,registry.get(first.runtimeId()).state());
        assertEquals(RuntimeState.RUNNING,replacement.state());
        assertEquals(RuntimeState.TERMINATED,capacity.release("worker:stable",replacement.runtimeId()).state());
    }
}
