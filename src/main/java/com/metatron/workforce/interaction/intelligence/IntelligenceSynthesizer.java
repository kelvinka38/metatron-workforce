package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;

@FunctionalInterface
public interface IntelligenceSynthesizer {
    String synthesize(IntelligenceRequest request, List<LlmResponse> responses);
}
