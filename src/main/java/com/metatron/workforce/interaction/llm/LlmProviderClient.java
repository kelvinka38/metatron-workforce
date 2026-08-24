package com.metatron.workforce.interaction.llm;

public interface LlmProviderClient {
    LlmProvider provider();

    LlmResponse complete(LlmRequest request);
}
