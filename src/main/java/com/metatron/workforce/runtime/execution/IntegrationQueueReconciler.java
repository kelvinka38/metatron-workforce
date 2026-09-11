package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttemptService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recovery for protected-ref integration entries. An integrating candidate survives restart only
 * while its ExecutionAttempt and canonical ResourceLease are still current; otherwise it is failed
 * closed and must be re-enqueued from fresh evidence.
 */
public final class IntegrationQueueReconciler {
    private final RepositoryIntegrationController integration;
    private final ExecutionAttemptService attempts;
    private final ExecutionResourceManager resources;

    public IntegrationQueueReconciler(RepositoryIntegrationController integration,
                                      ExecutionAttemptService attempts,
                                      ExecutionResourceManager resources) {
        this.integration = Objects.requireNonNull(integration, "integration");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public List<IntegrationQueueEntry> reconcile(Instant at) {
        Objects.requireNonNull(at, "at");
        List<IntegrationQueueEntry> changed = new ArrayList<>();
        for (IntegrationQueueEntry entry : integration.all()) {
            if (entry.terminal()) continue;
            boolean attemptCurrent;
            try {
                attempts.requireCurrent(entry.attemptId(), entry.attemptFencingToken(), at);
                attemptCurrent = true;
            } catch (RuntimeException stale) {
                attemptCurrent = false;
            }
            if (!attemptCurrent) {
                changed.add(integration.fail(entry.entryId(), "execution-attempt-not-current", at));
                continue;
            }
            if (entry.status() == IntegrationQueueEntry.Status.INTEGRATING) {
                ResourceLease lease = resources.findLease(entry.integrationLeaseId()).orElse(null);
                if (lease == null || !lease.activeAt(at)
                        || lease.fencingToken() != entry.integrationResourceFence()) {
                    changed.add(integration.fail(entry.entryId(), "integration-resource-lease-not-current", at));
                }
            }
        }
        return List.copyOf(changed);
    }
}
