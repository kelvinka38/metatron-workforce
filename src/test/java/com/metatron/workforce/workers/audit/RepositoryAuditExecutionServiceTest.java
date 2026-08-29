package com.metatron.workforce.workers.audit;

import com.metatron.workforce.runtime.WorkerRuntime;
import com.metatron.workforce.work.WorkService;
import com.metatron.workforce.work.WorkStateStore;
import com.metatron.workforce.workers.WorkerResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryAuditExecutionServiceTest {

    @Test
    void oneDispatchExecutesWorkerPersistsEvidenceAndCompletesWork() {
        WorkService work = new WorkService(memoryStore());
        RepositoryAuditExecutionService service = new RepositoryAuditExecutionService(
                work, new WorkerRuntime(), () -> context -> new WorkerResult(
                        "RepositoryAuditWorker", "PASS",
                        "source=github-api\nrepository=kelvinka38/bios\ncommitSha=0123456789abcdef0123456789abcdef01234567\nverdict=PASS",
                        Instant.now()));

        var receipt = service.execute("FOUNDER", "AUTHORITY-1", "AUTHORIZATION-1", "METATRON", "kelvinka38/bios");

        assertEquals("PASS", receipt.workerResult().status());
        assertEquals("COMPLETED", receipt.work().status().name());
        assertTrue(receipt.work().evidenceRefs().stream().anyMatch(x -> x.startsWith("runtime-evidence:")));
        assertTrue(receipt.work().evidenceRefs().contains("authority:AUTHORITY-1"));
        assertTrue(receipt.work().evidenceRefs().contains("authorization:AUTHORIZATION-1"));
        assertEquals(receipt.work().workId(), work.get(receipt.work().workId()).workId());
    }

    @Test
    void workerFailureCannotCompleteInstitutionalWork() {
        WorkService work = new WorkService(memoryStore());
        RepositoryAuditExecutionService service = new RepositoryAuditExecutionService(
                work, new WorkerRuntime(), () -> context -> new WorkerResult(
                        "RepositoryAuditWorker", "FAILED", "verdict=FAILED\nreason=upstream unavailable", Instant.now()));

        var receipt = service.execute("FOUNDER", "AUTHORITY-1", "AUTHORIZATION-1", "METATRON", "kelvinka38/bios");

        assertEquals("FAILED", receipt.workerResult().status());
        assertEquals("BLOCKED", receipt.work().status().name());
        assertNull(receipt.work().outcomeRef());
    }

    @Test
    void missingAuthorityOrAuthorizationIsRejectedBeforeWorkCreation() {
        WorkService work = new WorkService(memoryStore());
        RepositoryAuditExecutionService service = new RepositoryAuditExecutionService(
                work, new WorkerRuntime(), () -> context -> failWorker());

        assertThrows(IllegalArgumentException.class,
                () -> service.execute("FOUNDER", "", "AUTHORIZATION-1", "METATRON", "kelvinka38/bios"));
        assertThrows(IllegalArgumentException.class,
                () -> service.execute("FOUNDER", "AUTHORITY-1", "", "METATRON", "kelvinka38/bios"));
        assertTrue(work.all().isEmpty());
    }

    private static WorkerResult failWorker() {
        fail("worker must not execute when admission references are absent");
        return null;
    }

    private static WorkStateStore memoryStore() {
        return new WorkStateStore() {
            private List<com.metatron.workforce.work.InstitutionalWork> state = List.of();
            @Override public List<com.metatron.workforce.work.InstitutionalWork> load() { return state; }
            @Override public void save(List<com.metatron.workforce.work.InstitutionalWork> values) { state = List.copyOf(values); }
        };
    }
}
