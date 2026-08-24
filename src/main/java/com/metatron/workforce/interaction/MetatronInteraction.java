package com.metatron.workforce.interaction;

import com.metatron.workforce.phase3.ActorRef;

import java.util.Objects;

/** Canonical Human interaction entering Metatron after transport normalization. */
public record MetatronInteraction(
        ActorRef human,
        ActorRef target,
        String organizationContextId,
        String conversationId,
        String externalMessageReference,
        String text) {
    public MetatronInteraction {
        Objects.requireNonNull(human, "human");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(organizationContextId, "organizationContextId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(externalMessageReference, "externalMessageReference");
        Objects.requireNonNull(text, "text");
        if (human.type() != ActorRef.ActorType.HUMAN) throw new IllegalArgumentException("human must be HUMAN");
        if (target.type() != ActorRef.ActorType.WORKER) throw new IllegalArgumentException("target must be WORKER");
        if (organizationContextId.isBlank() || conversationId.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("interaction fields must not be blank");
        }
    }
}
