package com.metatron.workforce.execution;

import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionActualWorkContractTest {
    @Test
    void legacyEnvelopeWithoutActualWorkFailsClosed() {
        Assignment assignment = new Assignment("assignment-1", "worker-1");
        ExecutionRequest request = new ExecutionRequest(
                "execution-1", assignment, new Authorization("auth-1", "worker-1"), Instant.now());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ExecutionAdmissionService().admit(request));
        assertEquals("actual work missing", failure.getMessage());
    }

    @Test
    void legacyMutatingEnvelopePreservesExactWorkButFailsClosedWithoutGovernance() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-1", "repair real defect", "kelvinka38/metatron-workforce",
                "repository.code.patch", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"), List.of("test-report"));
        ExecutionRequest request = new ExecutionRequest(
                "execution-2", new Assignment("assignment-2", "worker-2"),
                new Authorization("auth-2", "worker-2"), work, Instant.now());

        assertSame(work, request.workSpec());
        GovernanceDeniedException denied = assertThrows(GovernanceDeniedException.class,
                () -> new ExecutionAdmissionService().admit(request));
        assertEquals("SOT_DISCOVERY_REQUIRED", denied.code());
    }

    @Test
    void readOnlyExecutionStillAdmitsWithoutMutationGovernance() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-read", "inspect repository", "kelvinka38/metatron-workforce",
                "repository.audit.read", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("inspection produced"), List.of("inspection-report"));
        ExecutionRequest request = new ExecutionRequest(
                "execution-read", new Assignment("assignment-read", "worker-read"),
                new Authorization("auth-read", "worker-read"), work, Instant.now());

        assertEquals(ExecutionState.ADMITTED, new ExecutionAdmissionService().admit(request));
        assertSame(work, request.workSpec());
    }
}
