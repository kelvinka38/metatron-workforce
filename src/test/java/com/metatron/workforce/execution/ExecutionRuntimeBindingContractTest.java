package com.metatron.workforce.execution;

import com.metatron.workforce.runtime.RuntimeInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionRuntimeBindingContractTest {

    @Test
    void executionRuntimeBindingPreservesIdentitySeparation() {
        RuntimeInstance runtime = new RuntimeInstance("worker-001");
        ExecutionRecord execution = new ExecutionRecord("exec-001", "assign-001", "worker-001");

        RuntimeBinding binding = new RuntimeBinding(execution, runtime);

        assertEquals("exec-001", binding.executionId());
        assertEquals(runtime.runtimeId(), binding.runtimeId());
        assertNotEquals(execution.executionId(), binding.runtimeId());
    }

    @Test
    void runtimeReplacementKeepsExecutionContinuityAnchor() {
        RuntimeExecutionContinuity continuity = new RuntimeExecutionContinuity(
                "exec-001",
                "runtime-old",
                "runtime-new"
        );

        assertEquals("exec-001", continuity.executionId());
        assertEquals("runtime-old", continuity.previousRuntimeId());
        assertEquals("runtime-new", continuity.currentRuntimeId());
    }
}
