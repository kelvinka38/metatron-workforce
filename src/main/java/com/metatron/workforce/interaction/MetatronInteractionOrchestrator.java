package com.metatron.workforce.interaction;

import java.util.Objects;

/**
 * Canonical orchestration boundary between Human interaction and Metatron nodes.
 * Provider selection and execution are delegated; this class does not bypass Gateway.
 */
public final class MetatronInteractionOrchestrator {
    private final InteractionHandler handler;

    public MetatronInteractionOrchestrator(InteractionHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public InteractionResponse handle(MetatronInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        return handler.handle(interaction);
    }

    @FunctionalInterface
    public interface InteractionHandler {
        InteractionResponse handle(MetatronInteraction interaction);
    }

    public record InteractionResponse(String conversationId, String text, String provenanceReference) {
        public InteractionResponse {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(text, "text");
            if (conversationId.isBlank() || text.isBlank()) throw new IllegalArgumentException("response fields must not be blank");
        }
    }
}
