package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

/** Immutable scheduling decision produced before any provider call is made. */
public record IntelligencePlan(
        String requestId,
        boolean requiresReasoning,
        IntelligenceMode mode,
        CollaborationMode collaborationMode,
        List<LlmProvider> providers) {

    public IntelligencePlan {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(providers, "providers");
        providers = List.copyOf(providers);
        if (requestId.isBlank()) throw new IllegalArgumentException("requestId must not be blank");
        if (providers.isEmpty()) throw new IllegalArgumentException("at least one provider is required for an intelligence plan");
        // SINGLE means one successful response is sufficient. Multiple providers are
        // therefore valid ordered failover candidates and are tried until one succeeds.
        if (collaborationMode != CollaborationMode.SINGLE && providers.size() < 2) {
            throw new IllegalArgumentException("multi-provider plan requires at least two providers");
        }
    }
}
