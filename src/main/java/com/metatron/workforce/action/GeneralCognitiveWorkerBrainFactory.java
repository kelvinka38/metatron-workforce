package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;

import java.util.List;
import java.util.Objects;

/** Produces one stateful provider-backed brain per Cognitive Worker execution. */
public final class GeneralCognitiveWorkerBrainFactory {
    private final LlmProviderRouter router;
    private final List<GeneralCognitiveWorkerBrain.ProviderRoute> providerRoutes;
    private final ObjectMapper json;

    public GeneralCognitiveWorkerBrainFactory(LlmProviderRouter router,
                                              LlmProvider provider,
                                              String model,
                                              ObjectMapper json) {
        this(router, List.of(new GeneralCognitiveWorkerBrain.ProviderRoute(provider, model)), json);
    }

    public GeneralCognitiveWorkerBrainFactory(
            LlmProviderRouter router,
            List<GeneralCognitiveWorkerBrain.ProviderRoute> providerRoutes,
            ObjectMapper json) {
        this.router = Objects.requireNonNull(router, "router");
        Objects.requireNonNull(providerRoutes, "providerRoutes");
        if (providerRoutes.isEmpty()) throw new IllegalArgumentException("worker cognitive provider routes required");
        this.providerRoutes = List.copyOf(providerRoutes);
        this.json = Objects.requireNonNull(json, "json");
    }

    public GeneralCognitiveWorkerBrain create() {
        return new GeneralCognitiveWorkerBrain(router, providerRoutes, json);
    }

    public LlmProvider provider() { return providerRoutes.getFirst().provider(); }
    public String model() { return providerRoutes.getFirst().model(); }
    public List<GeneralCognitiveWorkerBrain.ProviderRoute> providerRoutes() { return providerRoutes; }
}
