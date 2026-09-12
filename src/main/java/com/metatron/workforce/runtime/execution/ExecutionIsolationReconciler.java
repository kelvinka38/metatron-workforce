package com.metatron.workforce.runtime.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single recovery loop for execution isolation state. It invokes the canonical resource, integration
 * and workspace reconcilers; it does not create a new scheduler or execution authority.
 */
public final class ExecutionIsolationReconciler implements AutoCloseable {
    private final ResourceLeaseReconciler resourceLeases;
    private final IntegrationQueueReconciler integration;
    private final ExecutionWorkspaceReconciler workspaces;
    private final Clock clock;
    private final Duration interval;
    private final AtomicReference<Receipt> lastReceipt = new AtomicReference<>();
    private final ScheduledExecutorService executor;

    public ExecutionIsolationReconciler(ResourceLeaseReconciler resourceLeases,
                                        IntegrationQueueReconciler integration,
                                        ExecutionWorkspaceReconciler workspaces,
                                        Clock clock,
                                        Duration interval) {
        this.resourceLeases = Objects.requireNonNull(resourceLeases, "resourceLeases");
        this.integration = Objects.requireNonNull(integration, "integration");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("reconcile interval must be positive");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "metatron-execution-isolation-reconciler");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::runSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Receipt reconcileNow() {
        Instant at = clock.instant();
        List<ResourceLease> leaseChanges = resourceLeases.reconcile(at);
        List<IntegrationQueueEntry> integrationChanges = integration.reconcile(at);
        ExecutionWorkspaceReconciler.Report workspaceChanges = workspaces.reconcile(at);
        Receipt receipt = new Receipt(at, leaseChanges.size(), integrationChanges.size(), workspaceChanges, "PASS");
        lastReceipt.set(receipt);
        return receipt;
    }

    public Receipt lastReceipt() {
        Receipt receipt = lastReceipt.get();
        return receipt == null ? new Receipt(clock.instant(), 0, 0,
                new ExecutionWorkspaceReconciler.Report(0, 0, 0, 0, 0, List.of()), "NOT_RUN") : receipt;
    }

    private void runSafely() {
        try {
            reconcileNow();
        } catch (RuntimeException failure) {
            lastReceipt.set(new Receipt(clock.instant(), 0, 0,
                    new ExecutionWorkspaceReconciler.Report(0, 0, 0, 0, 0, List.of()),
                    "FAILED:" + failure.getClass().getSimpleName()));
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public record Receipt(Instant at, int resourceLeaseChanges, int integrationChanges,
                          ExecutionWorkspaceReconciler.Report workspaceChanges, String status) { }
}
