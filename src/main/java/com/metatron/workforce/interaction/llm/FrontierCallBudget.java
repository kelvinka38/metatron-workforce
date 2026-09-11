package com.metatron.workforce.interaction.llm;

import java.util.Objects;
import java.util.Set;

/**
 * Low-level, provider-neutral hard budget carried with one logical frontier request.
 * The Intelligence layer owns policy and converts its ProviderBudget into this transport-safe shape.
 */
public record FrontierCallBudget(
        int maxInitialCalls,
        int maxFallbackCalls,
        int maxEscalationCalls,
        boolean multiModelAllowed,
        Set<String> allowedEscalationReasons,
        boolean enforced) {

    public FrontierCallBudget {
        if (maxInitialCalls < 0 || maxFallbackCalls < 0 || maxEscalationCalls < 0) {
            throw new IllegalArgumentException("frontier call budgets must be non-negative");
        }
        Objects.requireNonNull(allowedEscalationReasons, "allowedEscalationReasons");
        allowedEscalationReasons = Set.copyOf(allowedEscalationReasons);
    }

    /** Compatibility mode for legacy/test callers that have not entered the Cognitive Runtime yet. */
    public static FrontierCallBudget legacyUnbounded() {
        return new FrontierCallBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                true, Set.of(), false);
    }

    public int maxTotalCalls() {
        long total = (long) maxInitialCalls + maxFallbackCalls + maxEscalationCalls;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    public boolean allowsEscalation(String reasonCode) {
        if (!enforced) return true;
        if (reasonCode == null || reasonCode.isBlank()) return false;
        return allowedEscalationReasons.contains(reasonCode.trim());
    }
}
