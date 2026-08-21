package com.metatron.workforce.phase3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkQueueServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T06:00:00Z");
    private static final ActorRef HUMAN = new ActorRef("human-1", ActorRef.ActorType.HUMAN);
    private static final ActorRef WORKER = new ActorRef("worker-1", ActorRef.ActorType.WORKER);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void createsAndTracksQueueLifecycle() {
        WorkQueueService service = new WorkQueueService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-queue"), CLOCK);

        WorkQueueItem item = service.create(
                WORKER, "org-1", HUMAN, WorkQueueItem.ItemType.REQUEST,
                "work-1", WorkQueueItem.Priority.HIGH, NOW.plusSeconds(3600));

        assertEquals(WorkQueueItem.QueueState.CREATED, item.state());
        item = service.deliver(item.queueItemId());
        item = service.acknowledge(item.queueItemId());
        item = service.resolve(item.queueItemId());
        assertEquals(WorkQueueItem.QueueState.RESOLVED, item.state());
        assertEquals(NOW, item.createdAt());
    }

    @Test
    void rejectsUnauthorizedQueueCreation() {
        WorkQueueService service = new WorkQueueService(
                (actor, target, org) -> AuthorizationContext.denied("auth-denied"), CLOCK);

        assertThrows(SecurityException.class, () -> service.create(
                WORKER, "org-1", HUMAN, WorkQueueItem.ItemType.REQUEST,
                "work-1", WorkQueueItem.Priority.NORMAL, null));
        assertEquals(0, service.items().size());
    }

    @Test
    void preservesReferencedObjectAndAuthorizationWhenQueueStateChanges() {
        WorkQueueService service = new WorkQueueService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-queue"), CLOCK);

        WorkQueueItem item = service.create(
                WORKER, "org-1", HUMAN, WorkQueueItem.ItemType.ASSIGNMENT_REFERENCE,
                "assignment-42", WorkQueueItem.Priority.NORMAL, NOW.plusSeconds(100));
        item = service.deliver(item.queueItemId());
        item = service.acknowledge(item.queueItemId());
        item = service.escalate(item.queueItemId());

        assertEquals("assignment-42", item.referencedObjectId());
        assertEquals("auth-queue", item.authorizationId());
        assertEquals(WorkQueueItem.QueueState.ESCALATED, item.state());
    }

    @Test
    void detectsOverdueOutstandingItemWithoutChangingQueueState() {
        WorkQueueService service = new WorkQueueService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-queue"), CLOCK);
        WorkQueueItem item = service.create(
                WORKER, "org-1", HUMAN, WorkQueueItem.ItemType.REVIEW_REQUEST,
                "review-1", WorkQueueItem.Priority.HIGH, NOW.minusSeconds(1));

        assertEquals(1, service.outstandingAt(NOW).size());
        assertFalse(service.isOverdue(item, NOW.minusSeconds(2)));
        assertEquals(true, service.isOverdue(item, NOW));
        assertEquals(WorkQueueItem.QueueState.CREATED, service.items().getFirst().state());
    }

    @Test
    void invalidTransitionDoesNotChangeState() {
        WorkQueueService service = new WorkQueueService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-queue"), CLOCK);
        WorkQueueItem item = service.create(
                WORKER, "org-1", HUMAN, WorkQueueItem.ItemType.NOTIFICATION,
                "notification-1", WorkQueueItem.Priority.LOW, null);

        assertThrows(IllegalStateException.class, () -> service.resolve(item.queueItemId()));
        assertEquals(WorkQueueItem.QueueState.CREATED, service.items().getFirst().state());
    }
}
