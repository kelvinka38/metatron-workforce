package com.metatron.workforce.interaction.llm;

import java.util.Map;
import java.util.Objects;

public record LlmResponse(
        LlmProvider provider,
        String model,
        String text,
        String providerRequestReference,
        LlmUsage usage,
        Map<String, String> providerTelemetry) {

    /** Backward-compatible constructor for providers/tests without usage metadata. */
    public LlmResponse(LlmProvider provider, String model, String text, String providerRequestReference) {
        this(provider, model, text, providerRequestReference, LlmUsage.UNKNOWN_USAGE, Map.of());
    }

    public LlmResponse {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(providerTelemetry, "providerTelemetry");
        providerTelemetry = Map.copyOf(providerTelemetry);
        if (text.isBlank()) throw new IllegalArgumentException("text must not be blank");
    }
}
