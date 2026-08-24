package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Objects;

/** Governed result envelope; raw provider outputs remain attributable. */
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
        if (providerResults.isEmpty()) throw new IllegalArgumentException("at least one provider result is required");
    }

    public record ProviderResult(LlmProvider provider, LlmResponse response) {
        public ProviderResult {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(response, "response");
        }
    }
}
