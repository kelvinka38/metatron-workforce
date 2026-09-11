package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;

/** Provider-neutral execution boundary for scarce cognition. */
@FunctionalInterface
public interface IntelligenceEngine {
    LlmResponse execute(LlmProvider provider, IntelligenceRequest request);

    /**
     * Escalation-aware overload. Existing engines remain compatible; institutional engines should
     * override this method so second-or-later frontier calls can carry machine-recordable reasons.
     */
    default LlmResponse execute(LlmProvider provider,
                                IntelligenceRequest request,
                                EscalationReason escalationReason) {
        return execute(provider, request);
    }

    /** Hard-budget-aware overload used by the Cognitive Runtime. */
    default LlmResponse execute(LlmProvider provider,
                                IntelligenceRequest request,
                                EscalationReason escalationReason,
                                ProviderBudget providerBudget) {
        return execute(provider, request, escalationReason);
    }
}
