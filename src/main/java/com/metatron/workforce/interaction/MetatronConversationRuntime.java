package com.metatron.workforce.interaction;

import com.metatron.workforce.interaction.intelligence.IntelligenceDepthContract;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepthControlService;
import com.metatron.workforce.interaction.intelligence.MetatronIntelligenceResponder;
import com.metatron.workforce.interaction.memory.ConversationMemoryStore;

import java.util.Objects;

/** Canonical channel-independent conversational runtime. Every human channel enters here after transport normalization. */
public final class MetatronConversationRuntime {
    private final ConversationMemoryStore memory;
    private final MetatronIntelligenceResponder intelligence;
    private final IntelligenceDepthControlService depthControl;
    private final int maxTurns;
    private final int maxChars;

    /** Backward-compatible composition: semantic AUTO depth when no explicit control service is supplied. */
    public MetatronConversationRuntime(ConversationMemoryStore memory,
                                       MetatronIntelligenceResponder intelligence,
                                       int maxTurns,
                                       int maxChars) {
        this(memory, intelligence, null, maxTurns, maxChars);
    }

    public MetatronConversationRuntime(ConversationMemoryStore memory,
                                       MetatronIntelligenceResponder intelligence,
                                       IntelligenceDepthControlService depthControl,
                                       int maxTurns,
                                       int maxChars) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.depthControl = depthControl;
        if (maxTurns < 1 || maxChars < 1) throw new IllegalArgumentException("memory limits must be positive");
        this.maxTurns = maxTurns;
        this.maxChars = maxChars;
    }

    public MetatronInteractionOrchestrator.InteractionResponse handle(MetatronInteraction interaction, String channel) {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(channel, "channel");
        if (channel.isBlank()) throw new IllegalArgumentException("channel must not be blank");

        if (depthControl != null) {
            IntelligenceDepthControlService.ControlResult control = depthControl.handle(
                    interaction.conversationId(), interaction.text());
            if (control.controlHandled()) {
                memory.appendTurn(interaction.conversationId(), interaction.text(), control.response());
                return new MetatronInteractionOrchestrator.InteractionResponse(
                        interaction.conversationId(), control.response(),
                        "intelligence-depth-control:" + interaction.externalMessageReference());
            }
        }

        String history = memory.contextFor(
                interaction.conversationId(), interaction.text(), maxTurns,
                Math.max(4, maxTurns / 4), maxChars);
        IntelligenceDepthContract contract = depthControl == null
                ? IntelligenceDepthContract.automatic()
                : depthControl.contract(interaction.conversationId());
        String answer = intelligence.respond(
                interaction.human().actorId(), interaction.text(), interaction.externalMessageReference(),
                channel, interaction.conversationId(), interaction.organizationContextId(), history, contract);
        memory.appendTurn(interaction.conversationId(), interaction.text(), answer);

        return new MetatronInteractionOrchestrator.InteractionResponse(
                interaction.conversationId(), answer,
                "interaction:" + interaction.externalMessageReference());
    }
}
