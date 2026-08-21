package com.metatron.workforce.phase3;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Operational queue coordination. Queue state never mutates the referenced domain object. */
public final class WorkQueueService {
    private final AuthorizationPolicy authorizationPolicy;
    private final Clock clock;
    private final List<WorkQueueItem> items = new ArrayList<>();

    public WorkQueueService(AuthorizationPolicy authorizationPolicy, Clock clock) {
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy, "authorizationPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkQueueItem create(
            ActorRef recipient,
            String organizationContextId,
            ActorRef sourceActor,
            WorkQueueItem.ItemType itemType,
            String referencedObjectId,
            WorkQueueItem.Priority priority,
            Instant dueAt) {
        AuthorizationContext authorization = authorizationPolicy.authorize(
                sourceActor, recipient, organizationContextId);
        requireAllowed(authorization);

        WorkQueueItem item = new WorkQueueItem(
                UUID.randomUUID().toString(), recipient, organizationContextId, sourceActor,
                itemType, referencedObjectId, priority, Instant.now(clock), dueAt,
                WorkQueueItem.QueueState.CREATED, authorization.authorizationId());
        items.add(item);
        return item;
    }

    public WorkQueueItem deliver(String queueItemId) {
        return transition(queueItemId, WorkQueueItem.QueueState.CREATED, WorkQueueItem.QueueState.DELIVERED);
    }

    public WorkQueueItem acknowledge(String queueItemId) {
        return transition(queueItemId, WorkQueueItem.QueueState.DELIVERED, WorkQueueItem.QueueState.ACKNOWLEDGED);
    }

    public WorkQueueItem resolve(String queueItemId) {
        return transition(queueItemId, WorkQueueItem.QueueState.ACKNOWLEDGED, WorkQueueItem.QueueState.RESOLVED);
    }

    public WorkQueueItem dismiss(String queueItemId) {
        return transition(queueItemId, WorkQueueItem.QueueState.ACKNOWLEDGED, WorkQueueItem.QueueState.DISMISSED);
    }

    public WorkQueueItem escalate(String queueItemId) {
        WorkQueueItem item = find(queueItemId);
        if (item.state() != WorkQueueItem.QueueState.ACKNOWLEDGED && item.state() != WorkQueueItem.QueueState.DELIVERED) {
            throw new IllegalStateException("queue item must be DELIVERED or ACKNOWLEDGED before escalation");
        }
        return replace(item, WorkQueueItem.QueueState.ESCALATED);
    }

    public List<WorkQueueItem> items() { return List.copyOf(items); }

    public List<WorkQueueItem> outstandingAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return items.stream()
                .filter(item -> item.state() != WorkQueueItem.QueueState.RESOLVED
                        && item.state() != WorkQueueItem.QueueState.DISMISSED)
                .toList();
    }

    public boolean isOverdue(WorkQueueItem item, Instant now) {
        return item.dueAt() != null && now.isAfter(item.dueAt())
                && item.state() != WorkQueueItem.QueueState.RESOLVED
                && item.state() != WorkQueueItem.QueueState.DISMISSED;
    }

    private WorkQueueItem transition(String id, WorkQueueItem.QueueState expected, WorkQueueItem.QueueState next) {
        WorkQueueItem item = find(id);
        if (item.state() != expected) {
            throw new IllegalStateException("invalid queue transition: " + item.state() + " -> " + next);
        }
        return replace(item, next);
    }

    private WorkQueueItem find(String id) {
        return items.stream().filter(item -> item.queueItemId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("queue item not found: " + id));
    }

    private WorkQueueItem replace(WorkQueueItem item, WorkQueueItem.QueueState state) {
        WorkQueueItem next = new WorkQueueItem(item.queueItemId(), item.recipient(), item.organizationContextId(),
                item.sourceActor(), item.itemType(), item.referencedObjectId(), item.priority(), item.createdAt(),
                item.dueAt(), state, item.authorizationId());
        items.set(items.indexOf(item), next);
        return next;
    }

    private static void requireAllowed(AuthorizationContext authorization) {
        Objects.requireNonNull(authorization, "authorization");
        if (!authorization.allowed()) throw new SecurityException(
                "queue action denied by authorization context " + authorization.authorizationId());
    }
}
