package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.Objects;

/** Snapshot of shared provider capacity used for routing decisions. */
public record ProviderCapacity(
        LlmProvider provider,
        boolean available,
        int priority,
        int availableConcurrency,
        long availableTokens,
        long estimatedLatencyMillis,
        long estimatedCostMicros) {

    public ProviderCapacity {
        Objects.requireNonNull(provider, "provider");
        if (priority < 0) throw new IllegalArgumentException("priority must be >= 0");
        if (availableConcurrency < 0) throw new IllegalArgumentException("availableConcurrency must be >= 0");
        if (availableTokens < 0) throw new IllegalArgumentException("availableTokens must be >= 0");
        if (estimatedLatencyMillis < 0) throw new IllegalArgumentException("estimatedLatencyMillis must be >= 0");
        if (estimatedCostMicros < 0) throw new IllegalArgumentException("estimatedCostMicros must be >= 0");
    }
}
