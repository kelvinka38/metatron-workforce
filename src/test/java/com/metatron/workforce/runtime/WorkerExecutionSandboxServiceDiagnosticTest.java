package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerExecutionSandboxServiceDiagnosticTest {
    @Test
    void boundedFailureDetailIsSingleLineAndBounded() {
        String detail = WorkerExecutionSandboxService.boundedFailureDetail(
                new IOException("connection refused\nsecond line"));
        assertEquals("IOException:connection refused second line", detail);
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\r"));

        String longDetail = WorkerExecutionSandboxService.boundedFailureDetail(
                new IOException("x".repeat(500)));
        assertTrue(longDetail.length() <= 240);
        assertTrue(longDetail.startsWith("IOException:"));
    }
}
