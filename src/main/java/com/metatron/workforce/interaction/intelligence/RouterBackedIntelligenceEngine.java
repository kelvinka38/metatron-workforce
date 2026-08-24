package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.Objects;
import java.util.function.Function;

/** Adapts the existing low-level provider router into the shared Intelligence Fabric. */
public final class RouterBackedIntelligenceEngine implements IntelligenceEngine {
    private final LlmProviderRouter router;
    private final Function<LlmProvider, String> modelSelector;

    public RouterBackedIntelligenceEngine(
            LlmProviderRouter router,
            Function<LlmProvider, String> modelSelector) {
        this.router = Objects.requireNonNull(router, "router");
        this.modelSelector = Objects.requireNonNull(modelSelector, "modelSelector");
    }

    @Override
    public LlmResponse execute(LlmProvider provider, IntelligenceRequest request) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        String model = Objects.requireNonNull(modelSelector.apply(provider), "selected model");
        if (model.isBlank()) throw new IllegalArgumentException("selected model must not be blank");

        return router.complete(new LlmRequest(
                provider,
                model,
                systemContext(request),
                request.objective()));
    }

    private static String systemContext(IntelligenceRequest request) {
        return "METATRON INTELLIGENCE REQUEST\n"
                + "requester=" + request.requester() + "\n"
                + "mode=" + request.mode() + "\n"
                + "requiredCapability=" + request.requiredCapability() + "\n"
                + "consequence=" + request.consequence() + "\n"
                + "authorityContext=" + request.authorityContext() + "\n"
                + "context=" + request.context() + "\n"
                + "evidenceReferences=" + request.evidenceReferences();
    }
}
