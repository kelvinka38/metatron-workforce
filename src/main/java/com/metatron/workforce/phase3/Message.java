package com.metatron.workforce.phase3;

import java.time.Instant;
import java.util.Objects;

public record Message(
        String messageId,
        String conversationId,
        ActorRef sender,
        String contentReference,
        String organizationContextId,
        String authorizationId,
        Instant createdAt) {

    public Message {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(contentReference, "contentReference");
        Objects.requireNonNull(organizationContextId, "organizationContextId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (messageId.isBlank() || conversationId.isBlank() || contentReference.isBlank()) {
            throw new IllegalArgumentException("message identity, conversation identity and content reference are required");
        }
    }
}
