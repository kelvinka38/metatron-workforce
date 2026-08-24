package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;

@FunctionalInterface
public interface IntelligenceEngine {
    LlmResponse execute(LlmProvider provider, IntelligenceRequest request);
}
