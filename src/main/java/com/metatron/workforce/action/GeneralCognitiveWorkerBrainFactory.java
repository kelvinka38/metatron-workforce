package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;

import java.util.Objects;

/** Produces one stateful provider-backed brain for each governed Cognitive Worker execution. */
public final class GeneralCognitiveWorkerBrainFactory {
    private final LlmProviderRouter router;
    private final LlmProvider provider;
    private final String model;
    private final ObjectMapper json;

    public GeneralCognitiveWorkerBrainFactory(LlmProviderRouter router,
                                              LlmProvider provider,
                                              String model,
                                              ObjectMapper json) {
        this.router = Objects.requireNonNull(router, "router");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = model == null ? "" : model.trim();
        if (this.model.isBlank()) throw new IllegalArgumentException("worker cognitive model required");
        this.json = Objects.requireNonNull(json, "json");
    }

    public GeneralCognitiveWorkerBrain create() {
        return new GeneralCognitiveWorkerBrain(router, provider, model, json);
    }

    public LlmProvider provider() { return provider; }
    public String model() { return model; }
}
