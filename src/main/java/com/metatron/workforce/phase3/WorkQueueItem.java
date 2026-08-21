package com.metatron.workforce.phase3;

import java.time.Instant;
import java.util.Objects;

public record WorkQueueItem(
        String queueItemId,
        ActorRef recipient,
        String organizationContextId,
        ActorRef sourceActor,
        ItemType itemType,
        String referencedObjectId,
        Priority priority,
        Instant createdAt,
        Instant dueAt,
        QueueState state,
        String authorizationId) {

    public WorkQueueItem {
        Objects.requireNonNull(queueItemId, "queueItemId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(organizationContextId, "organizationContextId");
        Objects.requireNonNull(sourceActor, "sourceActor");
        Objects.requireNonNull(itemType, "itemType");
        Objects.requireNonNull(referencedObjectId, "referencedObjectId");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(authorizationId, "authorizationId");
        if (queueItemId.isBlank() || organizationContextId.isBlank() || referencedObjectId.isBlank() || authorizationId.isBlank()) {
            throw new IllegalArgumentException("queue item identity/context/reference fields must not be blank");
        }
    }

    public enum ItemType { REQUEST, ASSIGNMENT_REFERENCE, APPROVAL_REQUEST, REVIEW_REQUEST, REPORT, ESCALATION, MEETING_INVITATION, PROPOSAL, NOTIFICATION }
    public enum Priority { LOW, NORMAL, HIGH, CRITICAL }
    public enum QueueState { CREATED, DELIVERED, ACKNOWLEDGED, RESOLVED, DISMISSED, ESCALATED }
}
