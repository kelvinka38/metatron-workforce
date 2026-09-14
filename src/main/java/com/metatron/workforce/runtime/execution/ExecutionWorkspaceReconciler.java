package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Seals terminal attempt workspaces, retains them for evidence, then garbage-collects only when the
 * attempt is terminal, retention has elapsed, no canonical resource lease is active, no integration
 * entry still references the attempt, and the binding version being deleted is still current.
 */
public final class ExecutionWorkspaceReconciler {
    /**
     * Grace period before an orphaned binding (no ExecutionAttempt record at all) is reclaimed. Deliberately
     * far shorter than the normal evidence-retention window: there is no completed work to protect here,
     * only a possible provision-vs-persist race between two separate stores, which resolves in milliseconds
     * in practice. 5 minutes is a generous safety margin without reintroducing the multi-hour cleanup delay
     * that reusing the 24h retention window caused.
     */
    private static final Duration ORPHAN_GRACE_PERIOD = Duration.ofMinutes(5);

    private final ExecutionAttemptService attempts;

    private final ExecutionWorkspaceManager workspaces;
    private final ExecutionWorkspaceBindingStore bindings;
    private final ExecutionResourceManager resources;
    private final RepositoryIntegrationController integration;
    private final Duration retention;

    public ExecutionWorkspaceReconciler(ExecutionAttemptService attempts,
                                        ExecutionWorkspaceManager workspaces,
                                        ExecutionWorkspaceBindingStore bindings,
                                        ExecutionResourceManager resources,
                                        RepositoryIntegrationController integration,
                                        Duration retention) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.integration = Objects.requireNonNull(integration, "integration");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("workspace retention must be positive");
        }
    }

    public Report reconcile(Instant at) {
        Objects.requireNonNull(at, "at");
        int sealed = 0;
        int retained = 0;
        int disposed = 0;
        int blockedByLease = 0;
        int blockedByIntegration = 0;
        List<String> changed = new ArrayList<>();

        for (ExecutionWorkspaceBinding snapshot : bindings.load().values()) {
            if (snapshot.status() == ExecutionWorkspaceBinding.Status.DISPOSED) continue;
            ExecutionAttempt attempt = attempts.find(snapshot.attemptId()).orElse(null);

            if (attempt == null) {
                // Orphaned-binding fix (2026-09-14, tightened same day): a binding with no ExecutionAttempt
                // record has nothing legitimate to protect -- unlike a normal SEALED/RETAINED workspace,
                // there is no completed work whose evidence needs a review window. The only real reason to
                // wait at all is a brief provision-vs-persist race (the workspace binding and the attempt
                // record are written to two different stores) -- that resolves in milliseconds in practice,
                // not hours. Originally this reused the full 24h `retention` window out of excess caution,
                // which measurably slowed cleanup to ~1 reclaim per several minutes for no safety benefit.
                // A short, dedicated grace period is the correct, decisive fix, not a 24h wait for a
                // millisecond race.
                if (!snapshot.updatedAt().plus(ORPHAN_GRACE_PERIOD).isAfter(at)) {
                    ExecutionWorkspaceBinding removed = workspaces.reclaimOrphaned(snapshot.attemptId(), snapshot.stateVersion(), at);
                    disposed++;
                    changed.add(removed.workspaceId() + ":orphan-reclaimed");
                }
                continue;
            }
            if (!attempt.terminal()) continue;


            ExecutionWorkspaceBinding current = workspaces.get(snapshot.attemptId()).orElse(snapshot);
            if (current.mutable()) {
                current = workspaces.seal(current.attemptId(), current.attemptFencingToken(), at);
                sealed++;
                changed.add(current.workspaceId() + ":sealed");
            }
            if (current.status() == ExecutionWorkspaceBinding.Status.SEALED && current.retentionUntil() == null) {
                current = workspaces.retain(current.attemptId(), current.stateVersion(), at.plus(retention), at);
                retained++;
                changed.add(current.workspaceId() + ":retained");
            }
            if (current.status() != ExecutionWorkspaceBinding.Status.RETAINED
                    || current.retentionUntil() == null
                    || current.retentionUntil().isAfter(at)) {
                continue;
            }

            String currentAttemptId = current.attemptId();
            boolean activeLease = resources.activeLeases().stream()
                    .anyMatch(lease -> lease.attemptId().equals(currentAttemptId));
            if (activeLease) {
                blockedByLease++;
                continue;
            }
            boolean referenced = integration.all().stream()
                    .anyMatch(entry -> entry.attemptId().equals(currentAttemptId) && !entry.terminal());
            if (referenced) {
                blockedByIntegration++;
                continue;
            }
            ExecutionWorkspaceBinding removed = workspaces.dispose(currentAttemptId, current.stateVersion(), at);
            disposed++;
            changed.add(removed.workspaceId() + ":disposed");
        }
        return new Report(sealed, retained, disposed, blockedByLease, blockedByIntegration, List.copyOf(changed));
    }

    public record Report(int sealed, int retained, int disposed,
                         int blockedByLease, int blockedByIntegration,
                         List<String> changedWorkspaceRefs) { }
}
