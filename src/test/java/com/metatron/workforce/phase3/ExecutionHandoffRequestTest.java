package com.metatron.workforce.phase3;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionHandoffRequestTest {

    private static ExecutionHandoffRequest valid() {
        return new ExecutionHandoffRequest(
                "handoff-001",
                "worker-001",
                "assignment-001",
                "auth-001",
                "wp-001",
                "execution-001",
                Instant.parse("2026-08-21T07:00:00Z"));
    }

    @Test
    void preservesRequiredCrossDomainCorrelation() {
        var request = valid();

        assertEquals("worker-001", request.workerId());
        assertEquals("assignment-001", request.assignmentId());
        assertEquals("auth-001", request.authorizationId());
        assertEquals("wp-001", request.workPackageId());
        assertEquals("execution-001", request.executionId());
    }

    @Test
    void rejectsMissingRequiredIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionHandoffRequest(
                "handoff-001", " ", "assignment-001", "auth-001", "wp-001",
                "execution-001", Instant.now()));
    }

    @Test
    void rejectsMissingAuthorizationCorrelation() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionHandoffRequest(
                "handoff-001", "worker-001", "assignment-001", "", "wp-001",
                "execution-001", Instant.now()));
    }

    @Test
    void rejectsMissingExecutionIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionHandoffRequest(
                "handoff-001", "worker-001", "assignment-001", "auth-001", "wp-001",
                "", Instant.now()));
    }
}
