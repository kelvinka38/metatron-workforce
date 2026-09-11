package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryIntegrationControllerTest {
    @TempDir Path temp;
    private static final String REPO = "kelvinka38/metatron-workforce";
    private static final String BASE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_A = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HEAD_B = "cccccccccccccccccccccccccccccccccccccccc";

    @Test void secondCandidateOnOldBaseBecomesStaleInsteadOfOverwritingMain() {
        Instant t = Instant.parse("2026-09-12T00:00:00Z");
        Fixture f = fixture(t, Duration.ofMinutes(1));
        ExecutionAttempt b = f.attempt("db", "b", t);
        ExecutionAttempt a = f.attempt("da", "a", t);
        f.component(b, HEAD_B, t.plusSeconds(1));
        f.component(a, HEAD_A, t.plusSeconds(1));

        IntegrationQueueEntry eb = f.controller.enqueue(b.attemptId(), b.fencingToken(), "workforce", HEAD_B,
                List.of("src/B.java"), List.of("module:b"), List.of("ci:b:pass"), t.plusSeconds(2));
        IntegrationQueueEntry ea = f.controller.enqueue(a.attemptId(), a.fencingToken(), "workforce", HEAD_A,
                List.of("src/A.java"), List.of("module:a"), List.of("ci:a:pass"), t.plusSeconds(3));

        IntegrationQueueEntry integrating = f.controller.beginNext(REPO, BASE, t.plusSeconds(4)).orElseThrow();
        assertEquals(eb.entryId(), integrating.entryId());
        IntegrationQueueEntry merged = f.controller.markMerged(eb.entryId(), BASE, HEAD_B,
                integrating.integrationLeaseId(), integrating.integrationResourceFence(), t.plusSeconds(5));
        assertEquals(IntegrationQueueEntry.Status.MERGED, merged.status());

        assertTrue(f.controller.beginNext(REPO, HEAD_B, t.plusSeconds(6)).isEmpty());
        IntegrationQueueEntry stale = f.controller.find(ea.entryId()).orElseThrow();
        assertEquals(IntegrationQueueEntry.Status.STALE_BASE, stale.status());
        assertTrue(stale.reason().contains("actual-main=" + HEAD_B));
    }

    @Test void staleIntegrationResourceFenceCannotCommitAfterLeaseReassignment() {
        Instant t = Instant.parse("2026-09-12T00:00:00Z");
        Fixture f = fixture(t, Duration.ofSeconds(5));
        ExecutionAttempt a = f.attempt("da", "a", t);
        ExecutionAttempt b = f.attempt("db", "b", t);
        f.component(a, HEAD_A, t.plusSeconds(1));
        f.component(b, HEAD_B, t.plusSeconds(1));
        IntegrationQueueEntry ea = f.controller.enqueue(a.attemptId(), a.fencingToken(), "workforce", HEAD_A,
                List.of("src/A.java"), List.of(), List.of("ci:a:pass"), t.plusSeconds(2));
        IntegrationQueueEntry eb = f.controller.enqueue(b.attemptId(), b.fencingToken(), "workforce", HEAD_B,
                List.of("src/B.java"), List.of(), List.of("ci:b:pass"), t.plusSeconds(3));

        IntegrationQueueEntry first = f.controller.beginNext(REPO, BASE, t.plusSeconds(4)).orElseThrow();
        assertEquals(ea.entryId(), first.entryId());
        f.resources.reconcileExpired(t.plusSeconds(10));
        IntegrationQueueEntry second = f.controller.beginNext(REPO, BASE, t.plusSeconds(10)).orElseThrow();
        assertEquals(eb.entryId(), second.entryId());
        assertTrue(second.integrationResourceFence() > first.integrationResourceFence());

        assertThrows(SecurityException.class, () -> f.controller.markMerged(ea.entryId(), BASE, HEAD_A,
                first.integrationLeaseId(), first.integrationResourceFence(), t.plusSeconds(11)));
    }

    @Test void conflictGraphSeparatesSemanticOverlapFromProtectedMainSerialization() {
        Instant t = Instant.parse("2026-09-12T00:00:00Z");
        IntegrationQueueEntry a = entry("a", "attempt-a", HEAD_A, List.of("src/payments/A.java"), List.of("module:payments"), t);
        IntegrationQueueEntry b = entry("b", "attempt-b", HEAD_B, List.of("src/telegram/B.java"), List.of("module:telegram"), t.plusSeconds(1));
        assertTrue(ConflictGraph.detect(List.of(a, b)).isEmpty());

        IntegrationQueueEntry c = entry("c", "attempt-c", "dddddddddddddddddddddddddddddddddddddddd",
                List.of("src/payments"), List.of("module:payments:ledger"), t.plusSeconds(2));
        List<ConflictEdge> conflicts = ConflictGraph.detect(List.of(a, b, c));
        assertTrue(conflicts.stream().anyMatch(e -> e.kind() == ConflictEdge.Kind.PATH && e.leftEntryId().equals("a") && e.rightEntryId().equals("c")));
        assertTrue(conflicts.stream().anyMatch(e -> e.kind() == ConflictEdge.Kind.RESOURCE && e.leftEntryId().equals("a") && e.rightEntryId().equals("c")));
        assertTrue(conflicts.stream().noneMatch(e -> e.leftEntryId().equals("a") && e.rightEntryId().equals("b")));
    }

    private static IntegrationQueueEntry entry(String id, String attempt, String head, List<String> paths, List<String> resources, Instant at) {
        return new IntegrationQueueEntry(id, attempt, 1, "workforce", REPO, BASE, head, paths, resources,
                List.of("ci:pass"), IntegrationQueueEntry.Status.QUEUED, "queued", "", 0, "", 1, at, at);
    }

    private Fixture fixture(Instant at, Duration integrationLease) {
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        ExecutionWorkspaceManager workspaces = new ExecutionWorkspaceManager(temp, attempts, new InMemoryExecutionWorkspaceBindingStore());
        ExecutionResourceManager resources = new ExecutionResourceManager(attempts, new InMemoryResourceStateStore(), Map.of());
        RepositoryIntegrationController controller = new RepositoryIntegrationController(attempts, workspaces, resources,
                new InMemoryIntegrationQueueStore(), integrationLease);
        return new Fixture(attempts, workspaces, resources, controller);
    }

    private record Fixture(ExecutionAttemptService attempts, ExecutionWorkspaceManager workspaces,
                           ExecutionResourceManager resources, RepositoryIntegrationController controller) {
        ExecutionAttempt attempt(String dispatch, String step, Instant at) {
            return attempts.begin(dispatch, "objective:integration", step, "worker:" + step,
                    "assignment:" + step, "auth:integration", "runtime:" + step, 1, Duration.ofHours(1), at);
        }
        void component(ExecutionAttempt attempt, String head, Instant at) {
            workspaces.allocate(attempt.attemptId(), attempt.fencingToken(), at);
            workspaces.registerRepository(attempt.attemptId(), attempt.fencingToken(), REPO, "main", "workforce",
                    "metatron/" + attempt.stepId(), at.plusMillis(1));
            workspaces.markMaterialized(attempt.attemptId(), attempt.fencingToken(), "workforce", BASE,
                    "1111111111111111111111111111111111111111", at.plusMillis(2));
            workspaces.markCommitted(attempt.attemptId(), attempt.fencingToken(), "workforce", head, at.plusMillis(3));
        }
    }
}
