package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared intelligence capacity boundary above concrete provider transport. */
public final class IntelligenceFabric {
    private final IntelligencePlanner planner;
    private final IntelligenceEngine engine;
    private final IntelligenceSynthesizer synthesizer;
    private final IntelligenceGovernance governance;

    public IntelligenceFabric(
            IntelligencePlanner planner,
            IntelligenceEngine engine,
            IntelligenceSynthesizer synthesizer,
            IntelligenceGovernance governance) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        this.governance = Objects.requireNonNull(governance, "governance");
    }

    public IntelligencePlan plan(IntelligenceRequest request) {
        return planner.plan(request);
    }

    public IntelligenceResult execute(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        IntelligencePlan plan = planner.plan(request);
        List<LlmResponse> responses = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();

        for (LlmProvider provider : plan.providers()) {
            try {
                LlmResponse response = Objects.requireNonNull(
                        engine.execute(provider, request),
                        "intelligence engine response");
                if (response.provider() != provider) {
                    throw new IllegalStateException("provider attribution mismatch for " + provider);
                }
                responses.add(response);

                // SINGLE mode only needs the first successful provider. In multi-provider
                // mode we continue so the synthesizer receives every successful response.
                if (plan.collaborationMode() == CollaborationMode.SINGLE) {
                    break;
                }
            } catch (RuntimeException failure) {
                failures.add(new IllegalStateException(
                        "intelligence provider failed: " + provider + ": " + failure.getMessage(),
                        failure));
            }
        }

        if (responses.isEmpty()) {
            IllegalStateException failure = new IllegalStateException(
                    "all selected intelligence providers failed: " + plan.providers());
            failures.forEach(failure::addSuppressed);
            throw failure;
        }

        String text = plan.collaborationMode() == CollaborationMode.SINGLE
                ? responses.getFirst().text()
                : Objects.requireNonNull(synthesizer.synthesize(request, List.copyOf(responses)),
                        "synthesized intelligence result");

        if (plan.requiresReasoning()) {
            governance.validate(request, List.copyOf(responses), text);
        }

        return new IntelligenceResult(
                request.requestId(),
                text,
                responses.stream()
                        .map(response -> new IntelligenceResult.ProviderResult(response.provider(), response))
                        .toList());
    }
}
