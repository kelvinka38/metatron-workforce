package com.metatron.workforce.interaction.llm;

import java.util.Objects;

public record LlmRequest(
        LlmProvider provider,
        String model,
        String systemContext,
        String userInput) {
    public LlmRequest {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemContext, "systemContext");
        Objects.requireNonNull(userInput, "userInput");
        if (model.isBlank() || userInput.isBlank()) throw new IllegalArgumentException("model and userInput must not be blank");
    }
}
