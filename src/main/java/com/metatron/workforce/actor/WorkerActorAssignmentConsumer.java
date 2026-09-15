package com.metatron.workforce.actor;

import com.metatron.workforce.core.WorkforceCoreService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Canonical durable Assignment consumer. It claims the projected Assignment message in the
 * assignee's serialized WorkerActor lane, then invokes the already-governed execution callback.
 */
public final class WorkerActorAssignmentConsumer implements AutoCloseable {
    private final WorkerActorRuntime actors;
    private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();

    public WorkerActorAssignmentConsumer(WorkerActorRuntime actors) {
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    public <T> CompletableFuture<T> submit(WorkforceCoreService.Assignment assignment,
                                            Function<WorkforceCoreService.Assignment, T> execution) {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(execution, "execution");
        actors.projectAssignment(assignment);
        return actors.consumeAssignment(assignment, () -> execution.apply(assignment));
    }

    private static String stepFrom(String id) {
        int p = id.indexOf(":work-step:");
        if (p < 0) return id;
        String value = id.substring(p + 11);
        int end = value.indexOf(':');
        return end < 0 ? value : value.substring(0, end);
    }

    @Override public void close() { dispatcher.shutdownNow(); }
}
