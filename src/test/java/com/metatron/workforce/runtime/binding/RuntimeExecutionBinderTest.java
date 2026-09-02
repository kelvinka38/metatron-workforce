package com.metatron.workforce.runtime.binding;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.RuntimeExecutionContext;
import com.metatron.workforce.runtime.RuntimeInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeExecutionBinderTest {

    @Test
    void bindingShouldPreserveExecutionIdentityAndActualWorkIndependentFromRuntime() {
        RuntimeInstance runtime = new RuntimeInstance("worker-100");
        RuntimeExecutionBinder binder = new RuntimeExecutionBinder();
        ExecutionWorkSpec work = work("step-001");

        RuntimeExecutionContext context = binder.bind(
                runtime,
                "execution-001",
                "assignment-001",
                "authorization-001",
                work
        );

        assertEquals(runtime.runtimeId(), context.runtimeId());
        assertEquals("worker-100", context.workerId());
        assertEquals("execution-001", context.executionId());
        assertEquals("assignment-001", context.assignmentId());
        assertEquals("authorization-001", context.authorizationId());
        assertSame(work, context.workSpec());
        assertEquals("step-001", context.workSpec().stepId());
        assertEquals("Audit repository", context.workSpec().objective());
        assertNotEquals(context.runtimeId(), context.workerId());
    }

    @Test
    void bindingRejectsMissingWork() {
        RuntimeInstance runtime = new RuntimeInstance("worker-150");
        RuntimeExecutionBinder binder = new RuntimeExecutionBinder();

        assertThrows(NullPointerException.class,
                () -> binder.bind(runtime, "execution-001", "assignment-001", "authorization-001", null));
    }

    @Test
    void failedRuntimeCannotReceiveExecutionBinding() {
        RuntimeInstance runtime = new RuntimeInstance("worker-200");
        runtime.transition(com.metatron.workforce.runtime.RuntimeState.FAILED);
        RuntimeExecutionBinder binder = new RuntimeExecutionBinder();

        assertThrows(
                IllegalStateException.class,
                () -> binder.bind(
                        runtime,
                        "execution-002",
                        "assignment-002",
                        "authorization-002",
                        work("step-002")
                )
        );
    }

    private static ExecutionWorkSpec work(String stepId) {
        return new ExecutionWorkSpec(
                stepId,
                "Audit repository",
                "kelvinka38/metatron-workforce",
                "repository.read",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("repository inspected"),
                List.of("source references"));
    }
}
