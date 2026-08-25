package com.metatron.workforce.interaction;

import com.metatron.workforce.bios.BiosExecutionKernel;

import java.util.Objects;

/**
 * Canonical orchestration boundary between Human interaction and Metatron nodes.
 * Every interaction is admitted and verified by the Workforce BIOS kernel before
 * provider/tool execution is reached.
 */
public final class MetatronInteractionOrchestrator {
    private final InteractionHandler handler;
    private final BiosExecutionKernel bios;

    public MetatronInteractionOrchestrator(InteractionHandler handler) {
        this(handler, new BiosExecutionKernel());
    }

    MetatronInteractionOrchestrator(InteractionHandler handler, BiosExecutionKernel bios) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.bios = Objects.requireNonNull(bios, "bios");
    }

    public InteractionResponse handle(MetatronInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        return bios.execute(interaction, handler::handle);
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
