package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Objects;

/**
 * Governed intelligence result envelope.
 * Provider results are present when an LLM contributed to the answer. They may be empty
 * for an evidence-only result produced directly from a governed tool capability; callers
 * must never invent provider attribution for such a result.
 */
public record IntelligenceResult(
        String requestId,
        String text,
        List<ProviderResult> providerResults) {

    public IntelligenceResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(providerResults, "providerResults");
        providerResults = List.copyOf(providerResults);
        if (requestId.isBlank() || text.isBlank()) throw new IllegalArgumentException("result fields must not be blank");
    }

    public boolean evidenceOnly() {
        return providerResults.isEmpty();
    }

    public record ProviderResult(LlmProvider provider, LlmResponse response) {
        public ProviderResult {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(response, "response");
        }
    }
}
