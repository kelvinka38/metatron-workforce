package com.metatron.workforce.actor;

import com.metatron.workforce.core.WorkforceCoreService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Elastic institutional Worker actor runtime.
 *
 * <p>Actor count is not tied to OS threads. Durable Worker actor identity/state/mailbox may scale
 * independently from active compute. Actor turns use shared virtual-thread compute with a bounded
 * global concurrency permit while a per-Worker lock preserves one serial work lane per Worker.</p>
 *
 * <p>The mailbox is durable evidence/routing state, not an alternate source of execution authority.
 * Canonical Workforce Assignment/Authorization/ExecutionAttempt remain authoritative for effects.</p>
 */
public final class WorkerActorRuntime implements AutoCloseable {
    private static final int MAX_MAILBOX_HISTORY_PER_WORKER = 2_000;

    private final WorkerActorStateStore store;
    private final Clock clock;
    private final ExecutorService executor;
    private final Semaphore computePermits;
    private final Map<String, WorkerActorSnapshot> actors = new LinkedHashMap<>();
    private final Map<String, List<WorkerActorMessage>> mailboxes = new LinkedHashMap<>();
    private final Map<String, ReentrantLock> lanes = new java.util.concurrent.ConcurrentHashMap<>();

    public WorkerActorRuntime(WorkerActorStateStore store, int maxConcurrentTurns) {
        this(store, maxConcurrentTurns, Clock.systemUTC());
    }

    WorkerActorRuntime(WorkerActorStateStore store, int maxConcurrentTurns, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxConcurrentTurns < 1) throw new IllegalArgumentException("maxConcurrentTurns must be positive");
        this.computePermits = new Semaphore(maxConcurrentTurns, true);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        WorkerActorStateStore.Snapshot loaded = store.load();
        actors.putAll(loaded.actors());
        loaded.mailboxes().forEach((workerId, messages) -> mailboxes.put(workerId, new ArrayList<>(messages)));
        markInterruptedTurnsForReconciliation();
    }

    public synchronized WorkerActorSnapshot ensureActor(String workerId) {
        String id = require(workerId, "workerId");
        WorkerActorSnapshot existing = actors.get(id);
        if (existing != null) return refreshDepth(existing);
        Instant now = clock.instant();
        WorkerActorSnapshot created = WorkerActorSnapshot.create(id, now);
        actors.put(id, created);
        mailboxes.computeIfAbsent(id, ignored -> new ArrayList<>());
        store.saveActor(created);
        store.saveMailbox(id, List.of());
        return created;
    }

    public synchronized Optional<WorkerActorSnapshot> find(String workerId) {
        WorkerActorSnapshot actor = actors.get(workerId);
        return actor == null ? Optional.empty() : Optional.of(refreshDepth(actor));
    }

    public synchronized WorkerActorSnapshot requireActor(String workerId) {
        return find(workerId).orElseThrow(() -> new IllegalStateException("worker actor not found: " + workerId));
    }

    public synchronized List<WorkerActorSnapshot> allActors() {
        return actors.values().stream()
                .map(this::refreshDepth)
                .sorted(Comparator.comparing(WorkerActorSnapshot::workerId))
                .toList();
    }

    public synchronized List<WorkerActorMessage> mailbox(String workerId) {
        ensureActor(workerId);
        return List.copyOf(mailboxes.getOrDefault(workerId, List.of()));
    }

    public synchronized int mailboxDepth(String workerId) {
        return mailboxDepthUnsafe(workerId);
    }

    public synchronized WorkerActorSnapshot pause(String workerId) {
        WorkerActorSnapshot actor = ensureActor(workerId);
        if (actor.state() == WorkerActorState.WORKING) {
            throw new IllegalStateException("cannot pause Worker actor while a turn is executing: " + workerId);
        }
        return update(actor.withState(WorkerActorState.PAUSED,
                actor.currentObjectiveId(), actor.currentAssignmentId(), actor.currentStepId(),
                "actor paused", "await founder resume", mailboxDepthUnsafe(workerId), clock.instant()));
    }

    public synchronized WorkerActorSnapshot resume(String workerId) {
        WorkerActorSnapshot actor = ensureActor(workerId);
        WorkerActorState next = mailboxDepthUnsafe(workerId) > 0 ? WorkerActorState.READY : WorkerActorState.IDLE;
        return update(actor.withState(next,
                actor.currentObjectiveId(), actor.currentAssignmentId(), actor.currentStepId(),
                "actor resumed", next == WorkerActorState.READY ? "consume mailbox" : "await work",
                mailboxDepthUnsafe(workerId), clock.instant()));
    }

    public synchronized WorkerActorSnapshot offline(String workerId) {
        WorkerActorSnapshot actor = ensureActor(workerId);
        if (actor.state() == WorkerActorState.WORKING) {
            throw new IllegalStateException("cannot take Worker actor offline while a turn is executing: " + workerId);
        }
        return update(actor.withState(WorkerActorState.OFFLINE,
                actor.currentObjectiveId(), actor.currentAssignmentId(), actor.currentStepId(),
                "actor offline", "await recovery", mailboxDepthUnsafe(workerId), clock.instant()));
    }

    public synchronized WorkerActorSnapshot heartbeat(String workerId) {
        WorkerActorSnapshot actor = ensureActor(workerId);
        return update(actor.heartbeat(mailboxDepthUnsafe(workerId), clock.instant()));
    }

    /**
     * Run one cognition/work turn as the canonical Worker actor. Calls for different Workers may run
     * concurrently; calls for the same Worker are serialized by that Worker's actor lane.
     */
    public <T> T runTurn(
            String workerId,
            WorkerActorMessage.Type type,
            String senderRef,
            String objectiveId,
            String assignmentId,
            String stepId,
            String capabilityRef,
            String payload,
            Callable<T> work) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(work, "work");
        String target = require(workerId, "workerId");
        ensureActor(target);
        WorkerActorMessage message = enqueue(new WorkerActorMessage(
                "actor-message:" + UUID.randomUUID(), target, type, WorkerActorMessage.Status.PENDING,
                safe(senderRef), safe(objectiveId), safe(assignmentId), safe(stepId), safe(capabilityRef), safe(payload),
                clock.instant(), null, null, ""));

        CompletableFuture<T> result = new CompletableFuture<>();
        executor.submit(() -> executeMessage(message.messageId(), work, result));
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker actor turn interrupted: " + target, interrupted);
        } catch (ExecutionException impossible) {
            Throwable cause = impossible.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("worker actor turn failed: " + target, cause);
        }
    }

    /** Durable idempotent projection of a canonical Assignment into the assignee's mailbox. */
    public synchronized WorkerActorMessage projectAssignment(WorkforceCoreService.Assignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        ensureActor(assignment.workerId());
        String messageId = "actor-message:assignment:" + assignment.assignmentId();
        WorkerActorMessage existing = findMessage(assignment.workerId(), messageId).orElse(null);
        if (existing == null) {
            existing = new WorkerActorMessage(
                    messageId,
                    assignment.workerId(),
                    WorkerActorMessage.Type.ASSIGNMENT,
                    WorkerActorMessage.Status.PENDING,
                    "workforce:assignment",
                    assignment.objectiveRef(),
                    assignment.assignmentId(),
                    "",
                    "",
                    assignment.description(),
                    assignment.createdAt(),
                    null,
                    null,
                    "");
            enqueue(existing);
        }
        return reconcileAssignmentMessage(assignment, existing);
    }

    /**
     * Durable Worker→Worker delegation envelope. This is routing/evidence only; it does not grant
     * capability or authority and must be converted to canonical Assignment by Workforce governance.
     */
    public synchronized WorkerActorMessage delegate(
            String fromWorkerId,
            String toWorkerId,
            String objectiveId,
            String payload) {
        ensureActor(require(fromWorkerId, "fromWorkerId"));
        ensureActor(require(toWorkerId, "toWorkerId"));
        return enqueue(new WorkerActorMessage(
                "actor-message:delegation:" + UUID.randomUUID(),
                toWorkerId,
                WorkerActorMessage.Type.DELEGATION,
                WorkerActorMessage.Status.PENDING,
                "worker:" + fromWorkerId,
                safe(objectiveId),
                "",
                "",
                "",
                safe(payload),
                clock.instant(), null, null, ""));
    }

    /** Reconcile truthful actor state from canonical Assignment truth after restart or between turns. */
    public synchronized WorkerActorSnapshot reconcileAssignments(
            String workerId,
            List<WorkforceCoreService.Assignment> assignments) {
        WorkerActorSnapshot actor = ensureActor(workerId);
        List<WorkforceCoreService.Assignment> owned = assignments == null ? List.of() : assignments.stream()
                .filter(Objects::nonNull)
                .filter(a -> workerId.equals(a.workerId()))
                .sorted(Comparator.comparing(WorkforceCoreService.Assignment::createdAt))
                .toList();
        owned.forEach(this::projectAssignment);

        actor = actors.get(workerId);
        if (actor.state() == WorkerActorState.WORKING || actor.state() == WorkerActorState.PAUSED
                || actor.state() == WorkerActorState.OFFLINE) return refreshDepth(actor);

        WorkforceCoreService.Assignment blocked = owned.stream()
                .filter(a -> a.status() == WorkforceCoreService.AssignmentStatus.BLOCKED)
                .findFirst().orElse(null);
        if (blocked != null) {
            return update(actor.withState(WorkerActorState.BLOCKED,
                    blocked.objectiveRef(), blocked.assignmentId(), "",
                    "canonical Assignment blocked", "await unblock/replan",
                    mailboxDepthUnsafe(workerId), clock.instant()));
        }
        WorkforceCoreService.Assignment active = owned.stream()
                .filter(a -> a.status() == WorkforceCoreService.AssignmentStatus.ACTIVE
                        || a.status() == WorkforceCoreService.AssignmentStatus.PLANNED)
                .findFirst().orElse(null);
        if (active != null) {
            return update(actor.withState(WorkerActorState.READY,
                    active.objectiveRef(), active.assignmentId(), "",
                    "canonical Assignment available", "consume assigned work",
                    mailboxDepthUnsafe(workerId), clock.instant()));
        }
        WorkerActorState next = mailboxDepthUnsafe(workerId) > 0 ? WorkerActorState.READY : WorkerActorState.IDLE;
        return update(actor.withState(next, "", "", "",
                "assignment reconciliation complete",
                next == WorkerActorState.READY ? "consume mailbox" : "await work",
                mailboxDepthUnsafe(workerId), clock.instant()));
    }

    public synchronized RuntimeStats stats() {
        long working = actors.values().stream().filter(a -> a.state() == WorkerActorState.WORKING).count();
        long ready = actors.values().stream().filter(a -> a.state() == WorkerActorState.READY).count();
        long blocked = actors.values().stream().filter(a -> a.state() == WorkerActorState.BLOCKED).count();
        long paused = actors.values().stream().filter(a -> a.state() == WorkerActorState.PAUSED).count();
        long pending = mailboxes.values().stream().flatMap(List::stream).filter(m -> !m.terminal()).count();
        return new RuntimeStats(actors.size(), working, ready, blocked, paused, pending,
                computePermits.availablePermits(), clock.instant());
    }

    private <T> void executeMessage(String messageId, Callable<T> work, CompletableFuture<T> result) {
        WorkerActorMessage envelope;
        synchronized (this) {
            envelope = findMessageById(messageId)
                    .orElseThrow(() -> new IllegalStateException("actor mailbox message missing: " + messageId));
        }
        String workerId = envelope.workerId();
        ReentrantLock lane = lanes.computeIfAbsent(workerId, ignored -> new ReentrantLock(true));
        boolean permit = false;
        lane.lock();
        try {
            computePermits.acquire();
            permit = true;
            synchronized (this) {
                WorkerActorSnapshot actor = ensureActor(workerId);
                if (actor.state() == WorkerActorState.PAUSED || actor.state() == WorkerActorState.OFFLINE) {
                    replaceMessage(envelope.fail("actor-not-runnable:" + actor.state(), clock.instant()));
                    result.completeExceptionally(new IllegalStateException(
                            "worker actor not runnable: " + workerId + ":" + actor.state()));
                    return;
                }
                WorkerActorMessage claimed = envelope.status() == WorkerActorMessage.Status.PENDING
                        ? replaceMessage(envelope.claim(clock.instant())) : envelope;
                envelope = claimed;
                update(actor.withState(WorkerActorState.WORKING,
                        claimed.objectiveId(), claimed.assignmentId(), claimed.stepId(),
                        "claimed " + claimed.type() + " " + claimed.messageId(),
                        "complete current actor turn", mailboxDepthUnsafe(workerId), clock.instant()));
            }

            T value = work.call();
            synchronized (this) {
                replaceMessage(envelope.complete(clock.instant()));
                WorkerActorSnapshot actor = requireActor(workerId);
                WorkerActorState next = mailboxDepthUnsafe(workerId) > 0 ? WorkerActorState.READY : WorkerActorState.IDLE;
                update(actor.withState(next, "", "", "",
                        "completed " + envelope.type() + " " + envelope.messageId(),
                        next == WorkerActorState.READY ? "consume mailbox" : "await work",
                        mailboxDepthUnsafe(workerId), clock.instant()));
            }
            result.complete(value);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failTurn(envelope, interrupted, result);
        } catch (Throwable failure) {
            failTurn(envelope, failure, result);
        } finally {
            if (permit) computePermits.release();
            lane.unlock();
        }
    }

    private <T> void failTurn(WorkerActorMessage envelope, Throwable failure, CompletableFuture<T> result) {
        synchronized (this) {
            WorkerActorMessage current = findMessage(envelope.workerId(), envelope.messageId()).orElse(envelope);
            if (!current.terminal()) replaceMessage(current.fail(safe(failure.getMessage()), clock.instant()));
            WorkerActorSnapshot actor = ensureActor(envelope.workerId());
            update(actor.withState(WorkerActorState.BLOCKED,
                    envelope.objectiveId(), envelope.assignmentId(), envelope.stepId(),
                    "actor turn failed: " + failure.getClass().getSimpleName(),
                    "await recovery/retry", mailboxDepthUnsafe(envelope.workerId()), clock.instant()));
        }
        if (failure instanceof RuntimeException runtime) result.completeExceptionally(runtime);
        else result.completeExceptionally(new IllegalStateException("worker actor turn failed", failure));
    }

    private synchronized WorkerActorMessage enqueue(WorkerActorMessage message) {
        ensureActor(message.workerId());
        List<WorkerActorMessage> box = mailboxes.computeIfAbsent(message.workerId(), ignored -> new ArrayList<>());
        WorkerActorMessage existing = box.stream()
                .filter(candidate -> candidate.messageId().equals(message.messageId()))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        box.add(message);
        trim(box);
        store.saveMailbox(message.workerId(), List.copyOf(box));

        WorkerActorSnapshot actor = actors.get(message.workerId());
        WorkerActorSnapshot next;
        if (actor.state() == WorkerActorState.IDLE) {
            next = actor.withState(WorkerActorState.READY,
                    message.objectiveId(), message.assignmentId(), message.stepId(),
                    "mailbox message enqueued", "consume mailbox", mailboxDepthUnsafe(message.workerId()), clock.instant());
        } else {
            next = actor.heartbeat(mailboxDepthUnsafe(message.workerId()), clock.instant());
        }
        actors.put(message.workerId(), next);
        store.saveActor(next);
        return message;
    }

    private synchronized WorkerActorMessage reconcileAssignmentMessage(
            WorkforceCoreService.Assignment assignment,
            WorkerActorMessage current) {
        WorkerActorMessage next = current;
        if ((assignment.status() == WorkforceCoreService.AssignmentStatus.COMPLETED
                || assignment.status() == WorkforceCoreService.AssignmentStatus.CANCELLED) && !current.terminal()) {
            next = replaceMessage(current.complete(clock.instant()));
        } else if (assignment.status() == WorkforceCoreService.AssignmentStatus.BLOCKED
                && current.status() == WorkerActorMessage.Status.CLAIMED) {
            next = replaceMessage(current.reconciliationRequired("canonical-assignment-blocked", clock.instant()));
        }
        return next;
    }

    private synchronized Optional<WorkerActorMessage> findMessage(String workerId, String messageId) {
        return mailboxes.getOrDefault(workerId, List.of()).stream()
                .filter(message -> message.messageId().equals(messageId)).findFirst();
    }

    private synchronized Optional<WorkerActorMessage> findMessageById(String messageId) {
        return mailboxes.values().stream().flatMap(List::stream)
                .filter(message -> message.messageId().equals(messageId)).findFirst();
    }

    private synchronized WorkerActorMessage replaceMessage(WorkerActorMessage replacement) {
        List<WorkerActorMessage> box = mailboxes.computeIfAbsent(replacement.workerId(), ignored -> new ArrayList<>());
        for (int i = 0; i < box.size(); i++) {
            if (box.get(i).messageId().equals(replacement.messageId())) {
                box.set(i, replacement);
                store.saveMailbox(replacement.workerId(), List.copyOf(box));
                return replacement;
            }
        }
        box.add(replacement);
        trim(box);
        store.saveMailbox(replacement.workerId(), List.copyOf(box));
        return replacement;
    }

    private synchronized WorkerActorSnapshot update(WorkerActorSnapshot actor) {
        actors.put(actor.workerId(), actor);
        store.saveActor(actor);
        return actor;
    }

    private WorkerActorSnapshot refreshDepth(WorkerActorSnapshot actor) {
        int depth = mailboxDepthUnsafe(actor.workerId());
        if (actor.mailboxDepth() == depth) return actor;
        WorkerActorSnapshot refreshed = actor.withMailboxDepth(depth);
        actors.put(actor.workerId(), refreshed);
        return refreshed;
    }

    private int mailboxDepthUnsafe(String workerId) {
        return (int) mailboxes.getOrDefault(workerId, List.of()).stream()
                .filter(message -> !message.terminal()).count();
    }

    private synchronized void markInterruptedTurnsForReconciliation() {
        Instant now = clock.instant();
        Set<String> changedMailboxes = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<WorkerActorMessage>> entry : mailboxes.entrySet()) {
            List<WorkerActorMessage> messages = entry.getValue();
            for (int i = 0; i < messages.size(); i++) {
                WorkerActorMessage message = messages.get(i);
                if (message.status() == WorkerActorMessage.Status.CLAIMED) {
                    messages.set(i, message.reconciliationRequired("process-restart-during-actor-turn", now));
                    changedMailboxes.add(entry.getKey());
                }
            }
        }
        changedMailboxes.forEach(workerId -> store.saveMailbox(workerId, List.copyOf(mailboxes.get(workerId))));

        for (Map.Entry<String, WorkerActorSnapshot> entry : new ArrayList<>(actors.entrySet())) {
            WorkerActorSnapshot actor = entry.getValue();
            if (actor.state() == WorkerActorState.WORKING) {
                WorkerActorSnapshot recovered = actor.recover(WorkerActorState.RECOVERING,
                        mailboxDepthUnsafe(entry.getKey()), now);
                actors.put(entry.getKey(), recovered);
                store.saveActor(recovered);
            }
        }
    }

    private void trim(List<WorkerActorMessage> box) {
        if (box.size() <= MAX_MAILBOX_HISTORY_PER_WORKER) return;
        int removable = box.size() - MAX_MAILBOX_HISTORY_PER_WORKER;
        for (int i = 0; i < box.size() && removable > 0;) {
            if (box.get(i).terminal()) {
                box.remove(i);
                removable--;
            } else i++;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public record RuntimeStats(
            long registeredActors,
            long workingActors,
            long readyActors,
            long blockedActors,
            long pausedActors,
            long pendingMailboxMessages,
            int availableComputePermits,
            Instant observedAt) {}

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
