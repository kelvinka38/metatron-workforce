package com.metatron.workforce.phase3;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Conversation(
        String conversationId,
        ActorRef initiator,
        List<ActorRef> participants,
        String organizationContextId,
        Instant createdAt) {

    public Conversation {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(initiator, "initiator");
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(organizationContextId, "organizationContextId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (conversationId.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");
        if (organizationContextId.isBlank()) throw new IllegalArgumentException("organizationContextId must not be blank");
        participants = List.copyOf(participants);
    }
}
