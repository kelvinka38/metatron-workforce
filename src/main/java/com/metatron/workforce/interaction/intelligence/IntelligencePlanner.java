package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

/** Builds an execution plan; provider transport is intentionally outside this component. */
public final class IntelligencePlanner {
    private final IntelligenceRoutingPolicy routingPolicy;

    public IntelligencePlanner(IntelligenceRoutingPolicy routingPolicy) {
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy");
    }

    public IntelligencePlan plan(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        List<LlmProvider> providers = List.copyOf(Objects.requireNonNull(
                routingPolicy.select(request), "routing policy result"));
        if (providers.isEmpty()) {
            throw new IllegalStateException("no intelligence capacity available for request: " + request.requestId());
        }
        if (providers.size() > request.maxProviders()) {
            throw new IllegalStateException("routing policy exceeded request provider budget: " + request.requestId());
        }
        if (request.collaborationMode() == CollaborationMode.SINGLE && providers.size() != 1) {
            throw new IllegalStateException("routing policy returned multiple providers for SINGLE request: " + request.requestId());
        }
        if (request.collaborationMode() != CollaborationMode.SINGLE && providers.size() < 2) {
            throw new IllegalStateException("routing policy returned insufficient providers for collaboration: " + request.requestId());
        }
        return new IntelligencePlan(
                request.requestId(),
                request.mode().requiresGovernance(),
                request.mode(),
                request.collaborationMode(),
                providers);
    }
}
