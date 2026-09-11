package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.FrontierCallBudget;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicit cognitive capacity budget. Depth changes investigation breadth, not default provider count.
 */
public record ProviderBudget(
        int maxInitialFrontierCalls,
        int maxFallbackCalls,
        int maxEscalationCalls,
        boolean multiModelAllowed,
        Set<EscalationReason> allowedEscalations) {

    public ProviderBudget {
        if (maxInitialFrontierCalls < 0 || maxFallbackCalls < 0 || maxEscalationCalls < 0) {
            throw new IllegalArgumentException("provider call budgets must be non-negative");
        }
        Objects.requireNonNull(allowedEscalations, "allowedEscalations");
        allowedEscalations = Set.copyOf(allowedEscalations);
    }

    public static ProviderBudget forDepth(IntelligenceDepth depth) {
        Objects.requireNonNull(depth, "depth");
        // Depth increases work inside the selected call, not provider count.
        return new ProviderBudget(
                1,
                2,
                1,
                false,
                EnumSet.allOf(EscalationReason.class));
    }

    public static ProviderBudget deterministicZeroCall() {
        return new ProviderBudget(0, 0, 0, false, Set.of());
    }

    public ProviderBudget withExplicitMultiModelRequest() {
        return withExplicitMultiModelRequest(2);
    }

    /**
     * Explicit multi-model work is exceptional but remains bounded. The semantic/first cognitive
     * pass already consumes the one initial call for the logical request, so every independent
     * provider analysis in the later multi-model phase is an escalation. The budget therefore
     * permits all requested independent providers, one normalization call, and at most one targeted
     * challenge per provider. Provider-failure retries remain governed separately by fallback budget.
     */
    public ProviderBudget withExplicitMultiModelRequest(int maxProviders) {
        int providers = Math.max(2, maxProviders);
        int boundedEscalations = Math.max(maxEscalationCalls, providers + 1 + providers);
        return new ProviderBudget(maxInitialFrontierCalls, maxFallbackCalls,
                boundedEscalations, true, allowedEscalations);
    }

    public int maxTotalFrontierCalls() {
        return maxInitialFrontierCalls + maxFallbackCalls + maxEscalationCalls;
    }

    public boolean allows(EscalationReason reason) {
        return allowedEscalations.contains(Objects.requireNonNull(reason, "reason"));
    }

    public FrontierCallBudget toFrontierCallBudget() {
        return new FrontierCallBudget(
                maxInitialFrontierCalls,
                maxFallbackCalls,
                maxEscalationCalls,
                multiModelAllowed,
                allowedEscalations.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                true);
    }
}
