package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only Control Room projection for execution workspaces, resource pressure and integration. */
@RestController
@RequestMapping("/workforce/monitor/api/execution-isolation")
public final class ExecutionIsolationObservabilityController {
    private final ExecutionWorkspaceBindingStore workspaces;
    private final ExecutionResourceManager resources;
    private final ExecutionResourceScheduler scheduler;
    private final RepositoryIntegrationController integration;
    private final ExecutionIsolationReconciler reconciler;
    private final ExecutionAttemptService attempts;

    public ExecutionIsolationObservabilityController(ExecutionWorkspaceBindingStore workspaces,
                                                      ExecutionResourceManager resources,
                                                      ExecutionResourceScheduler scheduler,
                                                      RepositoryIntegrationController integration,
                                                      ExecutionIsolationReconciler reconciler,
                                                      ExecutionAttemptService attempts) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.integration = Objects.requireNonNull(integration, "integration");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
    }

    @GetMapping
    public Snapshot snapshot(@RequestHeader("X-Metatron-Actor") String actor) {
        if (actor == null || actor.isBlank()) throw new SecurityException("execution isolation observability actor required");
        return snapshotNow();
    }

    /**
     * Founder-directed admin action (2026-09-14): force every currently non-terminal ExecutionAttempt
     * to ABANDONED regardless of lease expiry, then immediately reconciles so any workspace bindings
     * that are now eligible for disposal are processed in this same request rather than waiting for
     * the next scheduled tick. Intended for exactly the incident this shipped from: system confirmed
     * idle (zero waiting/admitted executions) with a backlog of attempts that would never resolve
     * their own lease naturally, whose backing workspace directories had already been removed from the
     * volume directly during disk-pressure triage. This is loopback-only (deploy-workforce-1 publishes
     * 127.0.0.1:8080 only) so the actor-header check matches the existing risk level of this
     * controller; it is not exposed beyond host-shell access.
     */
    @PostMapping("/force-abandon-idle")
    public ForceAbandonResult forceAbandonIdle(@RequestHeader("X-Metatron-Actor") String actor) {
        if (actor == null || actor.isBlank()) throw new SecurityException("execution isolation observability actor required");
        Instant now = Instant.now();
        List<ExecutionAttempt> abandoned = attempts.forceAbandonAllNonTerminal(
                "founder-directed-force-abandon-idle:actor=" + actor, now);
        ExecutionIsolationReconciler.Receipt receipt = reconciler.reconcileNow();
        return new ForceAbandonResult(now, abandoned.size(),
                abandoned.stream().map(ExecutionAttempt::attemptId).toList(), receipt, snapshotNow());
    }

    private Snapshot snapshotNow() {
        Instant now = Instant.now();
        Map<String, Long> workspaceStates = new LinkedHashMap<>();
        workspaces.load().values().forEach(binding -> workspaceStates.merge(binding.status().name(), 1L, Long::sum));
        Map<String, Long> integrationStates = new LinkedHashMap<>();
        integration.all().forEach(entry -> integrationStates.merge(entry.status().name(), 1L, Long::sum));
        List<LeaseView> leases = resources.activeLeases().stream()
                .map(lease -> new LeaseView(lease.resourceId(), lease.resourceClass().name(), lease.mode().name(),
                        lease.attemptId(), lease.fencingToken(), lease.expiresAt()))
                .toList();
        return new Snapshot(now, Map.copyOf(workspaceStates), scheduler.waitingCount(), scheduler.admittedCount(),
                resources.capacitySnapshot(now), leases, Map.copyOf(integrationStates), integration.conflicts(),
                reconciler.lastReceipt());
    }

    public record LeaseView(String resourceId, String resourceClass, String mode,
                            String attemptId, long resourceFence, Instant expiresAt) { }

    public record ForceAbandonResult(Instant at, int abandonedCount, List<String> abandonedAttemptIds,
                                     ExecutionIsolationReconciler.Receipt reconciliation, Snapshot after) { }

    public record Snapshot(Instant observedAt,
                           Map<String, Long> workspaceStates,
                           long waitingExecutions,
                           long admittedExecutions,
                           List<ResourceCapacitySnapshot> capacity,
                           List<LeaseView> activeLeases,
                           Map<String, Long> integrationStates,
                           List<ConflictEdge> integrationConflicts,
                           ExecutionIsolationReconciler.Receipt reconciliation) { }
}

