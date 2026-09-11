package com.metatron.workforce.runtime.execution;

import org.springframework.web.bind.annotation.GetMapping;
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

    public ExecutionIsolationObservabilityController(ExecutionWorkspaceBindingStore workspaces,
                                                      ExecutionResourceManager resources,
                                                      ExecutionResourceScheduler scheduler,
                                                      RepositoryIntegrationController integration,
                                                      ExecutionIsolationReconciler reconciler) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.integration = Objects.requireNonNull(integration, "integration");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    }

    @GetMapping
    public Snapshot snapshot(@RequestHeader("X-Metatron-Actor") String actor) {
        if (actor == null || actor.isBlank()) throw new SecurityException("execution isolation observability actor required");
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
