package com.metatron.workforce.actor;

import com.metatron.workforce.core.WorkforceCoreService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Truth projector from canonical Workforce identity/Assignment state into actor runtime state/mailboxes.
 * It never creates Assignment authority and never marks work complete on its own.
 */
public final class WorkerActorAssignmentReconciler implements AutoCloseable {
    private final WorkforceCoreService core;
    private final WorkerActorRuntime actors;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();

    public WorkerActorAssignmentReconciler(
            WorkforceCoreService core,
            WorkerActorRuntime actors,
            Duration interval) {
        this.core = Objects.requireNonNull(core, "core");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "metatron-worker-actor-reconciler");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            reconcileNow();
            scheduler.scheduleWithFixedDelay(this::reconcileSafely,
                    interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void reconcileNow() {
        for (WorkforceCoreService.Worker worker : core.allWorkers()) {
            switch (worker.status()) {
                case ACTIVE -> {
                    actors.ensureActor(worker.workerId());
                    actors.reconcileAssignments(worker.workerId(), core.assignments(worker.workerId()));
                }
                case SUSPENDED -> {
                    actors.ensureActor(worker.workerId());
                    WorkerActorSnapshot snapshot = actors.requireActor(worker.workerId());
                    if (snapshot.state() != WorkerActorState.WORKING
                            && snapshot.state() != WorkerActorState.PAUSED) {
                        actors.pause(worker.workerId());
                    }
                }
                case RETIRED -> {
                    actors.ensureActor(worker.workerId());
                    WorkerActorSnapshot snapshot = actors.requireActor(worker.workerId());
                    if (snapshot.state() != WorkerActorState.WORKING
                            && snapshot.state() != WorkerActorState.OFFLINE) {
                        actors.offline(worker.workerId());
                    }
                }
            }
        }
    }

    private void reconcileSafely() {
        try {
            reconcileNow();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(WorkerActorAssignmentReconciler.class)
                    .warn("worker_actor_reconciliation_failed: {}", failure.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
