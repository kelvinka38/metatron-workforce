package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;

/** Selects provider capacity without coupling Workforce to a vendor or transport. */
@FunctionalInterface
public interface IntelligenceRoutingPolicy {
    List<LlmProvider> select(IntelligenceRequest request);
}
