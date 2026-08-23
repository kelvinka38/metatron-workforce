package com.metatron.workforce.runtime.binding;

import com.metatron.workforce.runtime.RuntimeInstance;
import org.junit.jupiter.api.Test;
import com.metatron.workforce.runtime.RuntimeExecutionContext;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionBinderTest {

    @Test
    void bindingShouldPreserveExecutionIdentityIndependentFromRuntime() {

        RuntimeInstance runtime =
                new RuntimeInstance("worker-100");

        RuntimeExecutionBinder binder =
                new RuntimeExecutionBinder();

        RuntimeExecutionContext context =
                binder.bind(
                        runtime,
                        "execution-001",
                        "assignment-001",
                        "authorization-001"
                );

        assertEquals(
                runtime.runtimeId(),
                context.runtimeId()
        );

        assertEquals(
                "worker-100",
                context.workerId()
        );

        assertEquals(
                "execution-001",
                context.executionId()
        );

        assertNotEquals(
                context.runtimeId(),
                context.workerId()
        );
    }


    @Test
    void failedRuntimeCannotReceiveExecutionBinding() {

        RuntimeInstance runtime =
                new RuntimeInstance("worker-200");

        runtime.transition(
                com.metatron.workforce.runtime.RuntimeState.FAILED
        );

        RuntimeExecutionBinder binder =
                new RuntimeExecutionBinder();

        assertThrows(
                IllegalStateException.class,
                () -> binder.bind(
                        runtime,
                        "execution-002",
                        "assignment-002",
                        "authorization-002"
                )
        );
    }
}
