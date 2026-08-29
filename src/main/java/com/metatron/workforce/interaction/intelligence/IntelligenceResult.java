package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Objects;

/** Governed intelligence result envelope with provider attribution and evidence references. */
public record IntelligenceResult(
        String requestId,
        String text,
        List<ProviderResult> providerResults,
        List<String> evidenceReferences) {

    /** Backward-compatible constructor for results without separately surfaced evidence references. */
    public IntelligenceResult(String requestId, String text, List<ProviderResult> providerResults) {
        this(requestId, text, providerResults, List.of());
    }

    public IntelligenceResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(providerResults, "providerResults");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        providerResults = List.copyOf(providerResults);
        evidenceReferences = List.copyOf(evidenceReferences);
        if (requestId.isBlank() || text.isBlank()) throw new IllegalArgumentException("result fields must not be blank");
    }

    public boolean evidenceOnly() {
        return providerResults.isEmpty() && !evidenceReferences.isEmpty();
    }

    public record ProviderResult(LlmProvider provider, LlmResponse response) {
        public ProviderResult {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(response, "response");
        }
    }
}
