package com.metatron.workforce.phase3;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeExecutionBindingTest {

    private static ExecutionHandoffRequest handoff() {
        return new ExecutionHandoffRequest(
                "handoff-001", "worker-001", "assignment-001", "auth-001", "wp-001",
                "execution-001", Instant.parse("2026-08-21T07:00:00Z"));
    }

    @Test
    void bindsExecutionToMatchingActiveRuntime() {
        var binding = RuntimeExecutionBinding.bind(
                handoff(),
                new WorkerRuntimeInstance("runtime-001", "worker-001", WorkerRuntimeInstance.RuntimeState.ACTIVE));

        assertEquals("execution-001", binding.executionId());
        assertEquals("runtime-001", binding.runtimeId());
        assertEquals("worker-001", binding.workerId());
    }

    @Test
    void refusesRuntimeOwnedByDifferentWorker() {
        assertThrows(IllegalArgumentException.class, () -> RuntimeExecutionBinding.bind(
                handoff(),
                new WorkerRuntimeInstance("runtime-002", "worker-002", WorkerRuntimeInstance.RuntimeState.ACTIVE)));
    }

    @Test
    void refusesTerminatedRuntime() {
        assertThrows(IllegalStateException.class, () -> RuntimeExecutionBinding.bind(
                handoff(),
                new WorkerRuntimeInstance("runtime-003", "worker-001", WorkerRuntimeInstance.RuntimeState.TERMINATED)));
    }

    @Test
    void executionIdentityRemainsStableAcrossRuntimeReplacement() {
        var first = RuntimeExecutionBinding.bind(
                handoff(),
                new WorkerRuntimeInstance("runtime-a", "worker-001", WorkerRuntimeInstance.RuntimeState.ACTIVE));
        var replacement = RuntimeExecutionBinding.bind(
                handoff(),
                new WorkerRuntimeInstance("runtime-b", "worker-001", WorkerRuntimeInstance.RuntimeState.READY));

        assertEquals(first.executionId(), replacement.executionId());
        assertEquals("runtime-a", first.runtimeId());
        assertEquals("runtime-b", replacement.runtimeId());
    }
}
