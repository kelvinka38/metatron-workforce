package com.metatron.workforce.interaction.llm;

@FunctionalInterface
public interface LlmProviderClient {
    LlmProvider provider();

    LlmResponse complete(LlmRequest request);
}
