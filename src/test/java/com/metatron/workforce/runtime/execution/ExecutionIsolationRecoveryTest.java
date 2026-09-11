package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionIsolationRecoveryTest {
    @TempDir Path temp;

    @Test
    void terminalWorkspaceIsRetainedThenGcOnlyAfterCanonicalLeaseIsReconciled() {
        Instant t = Instant.now();
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        ExecutionAttempt attempt = attempts.begin("dispatch:recovery", "objective:recovery", "step:recovery",
                "worker:recovery", "assignment:recovery", "auth:recovery", "runtime:recovery",
                1, Duration.ofMinutes(5), t);

        InMemoryExecutionWorkspaceBindingStore bindingStore = new InMemoryExecutionWorkspaceBindingStore();
        ExecutionWorkspaceManager workspaces = new ExecutionWorkspaceManager(temp, attempts, bindingStore);
        ExecutionWorkspaceBinding binding = workspaces.allocate(attempt.attemptId(), attempt.fencingToken(), t);
        assertTrue(Files.isDirectory(Path.of(binding.rootPath())));

        ExecutionResourceManager resources = new ExecutionResourceManager(attempts,
                new InMemoryResourceStateStore(), Map.of());
        ResourceClaim claim = new ResourceClaim("claim:recovery", attempt.attemptId(), "prod:workforce",
                ResourceClaim.ResourceClass.ENVIRONMENT, ResourceClaim.Mode.LEASE_EXCLUSIVE,
                1, "lease", true, "", Map.of());
        ResourceGrant grant = resources.acquire(attempt.attemptId(), attempt.fencingToken(), attempt.workerId(),
                List.of(claim), Duration.ofMinutes(10), t);
        assertEquals(1, grant.leases().size());

        RepositoryIntegrationController integration = new RepositoryIntegrationController(attempts, workspaces,
                resources, new InMemoryIntegrationQueueStore(), Duration.ofMinutes(2));
        ExecutionWorkspaceReconciler workspaceReconciler = new ExecutionWorkspaceReconciler(attempts, workspaces,
                bindingStore, resources, integration, Duration.ofSeconds(10));
        ResourceLeaseReconciler resourceReconciler = new ResourceLeaseReconciler(resources);

        attempts.succeed(attempt.attemptId(), attempt.fencingToken(), t.plusSeconds(1));
        ExecutionWorkspaceReconciler.Report first = workspaceReconciler.reconcile(t.plusSeconds(2));
        assertEquals(1, first.sealed());
        assertEquals(1, first.retained());
        assertEquals(0, first.disposed());
        assertTrue(Files.isDirectory(Path.of(binding.rootPath())), "retained evidence workspace must still exist");

        assertEquals(1, resourceReconciler.reconcile(t.plusSeconds(3)).size(),
                "terminal attempt must fence/reconcile its resource lease");
        ExecutionWorkspaceReconciler.Report second = workspaceReconciler.reconcile(t.plusSeconds(13));
        assertEquals(1, second.disposed());
        assertFalse(Files.exists(Path.of(binding.rootPath())),
                "retention-expired terminal unleased unreferenced workspace may be garbage-collected");
    }
}
