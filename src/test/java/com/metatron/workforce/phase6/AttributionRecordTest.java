package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AttributionRecordTest {
    @Test
    void preservesOriginDecisionAuthorizationAssignmentWorkerRuntimeAndExecution() {
        Instant occurred = Instant.parse("2026-08-20T10:00:00Z");
        Instant recorded = Instant.parse("2026-08-20T10:00:05Z");

        AttributionRecord record = new AttributionRecord(
                "attr-001",
                "human-001",
                "head-001",
                "head-001",
                "worker-001",
                "runtime-001",
                "auth-001",
                "assignment-001",
                "exec-001",
                "EXECUTE_WORK",
                occurred,
                recorded,
                "evidence-001",
                "message-001");

        assertEquals("human-001", record.originatingActorId());
        assertEquals("head-001", record.decisionActorId());
        assertEquals("worker-001", record.responsibleWorkerId());
        assertEquals("runtime-001", record.runtimeInstanceId());
        assertEquals("exec-001", record.executionId());
        assertTrue(record.recordingLagExists());
    }

    @Test
    void rejectsMissingOriginAndEvidence() {
        Instant now = Instant.parse("2026-08-20T10:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> new AttributionRecord(
                "attr-002", "", null, null, "worker-001", null,
                null, null, "exec-002", "EXECUTE_WORK", now, now,
                "evidence-002", "prov-002"));

        assertThrows(IllegalArgumentException.class, () -> new AttributionRecord(
                "attr-003", "human-001", null, null, "worker-001", null,
                null, null, "exec-003", "EXECUTE_WORK", now, now,
                "", "prov-003"));
    }
}
