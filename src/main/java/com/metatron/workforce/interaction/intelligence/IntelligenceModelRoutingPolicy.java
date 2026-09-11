package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

/** Selects one provider-local model for a concrete Intelligence request. */
@FunctionalInterface
public interface IntelligenceModelRoutingPolicy {
    String select(LlmProvider provider, IntelligenceRequest request);
}
