package com.metatron.workforce.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionAuthorizationBindingTest {
    @Test
    void missingAuthorizationIdentifierFailsClosed() {
        Assignment assignment = new Assignment("a-1", "worker-1");
        ExecutionRequest request = new ExecutionRequest("e-1", assignment,
                new Authorization("", "worker-1"), Instant.now());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExecutionAdmissionService().admit(request));
        assertEquals("authorization identifier missing", error.getMessage());
    }

    @Test
    void authorizationMustBeBoundToAssignedWorker() {
        Assignment assignment = new Assignment("a-2", "worker-1");
        ExecutionRequest request = new ExecutionRequest("e-2", assignment,
                new Authorization("auth-external-1", "worker-2"), Instant.now());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ExecutionAdmissionService().admit(request));
        assertEquals("authorization worker mismatch", error.getMessage());
    }
}
