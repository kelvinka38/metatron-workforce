package com.metatron.workforce.interaction.llm;

import java.util.Objects;

public record LlmResponse(
        LlmProvider provider,
        String model,
        String text,
        String providerRequestReference) {
    public LlmResponse {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) throw new IllegalArgumentException("text must not be blank");
    }
}
