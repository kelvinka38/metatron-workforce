package com.metatron.workforce.runtime;

import com.metatron.workforce.workers.Worker;
import com.metatron.workforce.workers.WorkerResult;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorkerRuntimeTest {

    @Test
    void should_execute_worker_and_write_evidence() throws Exception {

        Worker worker = context -> new WorkerResult(
                "RuntimeEvidenceTestWorker",
                "PASS",
                "runtime evidence",
                Instant.now()
        );

        WorkerRuntime runtime = new WorkerRuntime();

        WorkerResult result =
                runtime.execute(
                        worker,
                        "task-001",
                        "verify runtime evidence persistence"
                );

        assertNotNull(result);
        assertEquals("PASS", result.status());
        assertEquals("RuntimeEvidenceTestWorker", result.worker());
        assertNotNull(result.evidence());
        assertTrue(Files.exists(Path.of("runtime-evidence")));
        assertNotNull(result.completedAt());
    }
}
