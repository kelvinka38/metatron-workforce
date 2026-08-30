package com.metatron.workforce.interaction;

import com.metatron.workforce.phase3.ActorRef;

import java.util.Objects;

/**
 * Canonical Human interaction entering Metatron after transport normalization.
 *
 * Institutional identity/conversation state is distinct from transport correlation. A channel
 * provider (Telegram, Zalo, Web, API, etc.) may supply external actor/conversation/message
 * references, but those references never replace canonical Metatron identities or conversation IDs.
 */
public record MetatronInteraction(
        ActorRef human,
        ActorRef target,
        String organizationContextId,
        String conversationId,
        String channelProvider,
        String externalActorReference,
        String externalConversationReference,
        String externalMessageReference,
        String text) {

    /** Backward-compatible constructor for callers without explicit transport correlation metadata. */
    public MetatronInteraction(
            ActorRef human,
            ActorRef target,
            String organizationContextId,
            String conversationId,
            String externalMessageReference,
            String text) {
        this(human, target, organizationContextId, conversationId,
                "unspecified", "", "", externalMessageReference, text);
    }

    public MetatronInteraction {
        Objects.requireNonNull(human, "human");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(organizationContextId, "organizationContextId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(channelProvider, "channelProvider");
        Objects.requireNonNull(externalActorReference, "externalActorReference");
        Objects.requireNonNull(externalConversationReference, "externalConversationReference");
        Objects.requireNonNull(externalMessageReference, "externalMessageReference");
        Objects.requireNonNull(text, "text");
        if (human.type() != ActorRef.ActorType.HUMAN) throw new IllegalArgumentException("human must be HUMAN");
        if (target.type() != ActorRef.ActorType.WORKER) throw new IllegalArgumentException("target must be WORKER");
        if (organizationContextId.isBlank() || conversationId.isBlank() || channelProvider.isBlank()
                || externalMessageReference.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("interaction fields must not be blank");
        }
    }
}
