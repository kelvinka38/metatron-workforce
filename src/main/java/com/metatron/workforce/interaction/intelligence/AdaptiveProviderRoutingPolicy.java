package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.ProviderTelemetryRegistry;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral AUTO routing over live measured provider health/capacity telemetry.
 * Explicit Human provider selection is preserved even when telemetry predicts failure; AUTO may adapt.
 */
public final class AdaptiveProviderRoutingPolicy implements IntelligenceRoutingPolicy {
    private final List<LlmProvider> configuredProviders;
    private final ProviderTelemetryRegistry telemetry;

    public AdaptiveProviderRoutingPolicy(List<LlmProvider> configuredProviders,
                                         ProviderTelemetryRegistry telemetry) {
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        Set<LlmProvider> distinct = new HashSet<>();
        for (LlmProvider provider : configuredProviders) {
            Objects.requireNonNull(provider, "configured provider");
            if (!distinct.add(provider)) throw new IllegalArgumentException("duplicate configured provider: " + provider);
        }
        this.configuredProviders = List.copyOf(configuredProviders);
    }

    @Override
    public List<LlmProvider> select(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.requestedProviders().isEmpty()) {
            return request.requestedProviders().stream()
                    .filter(configuredProviders::contains)
                    .limit(request.maxProviders())
                    .toList();
        }
        return rankConfiguredProviders(configuredProviders, telemetry).stream()
                .limit(request.maxProviders())
                .toList();
    }

    /**
     * Shared AUTO provider ordering for every frontier boundary, including semantic normalization
     * and post-Case execution planning. This keeps provider capacity management out of Worker identity
     * while allowing all cognition stages to avoid measured degraded capacity.
     */
    static List<LlmProvider> rankConfiguredProviders(List<LlmProvider> configuredProviders,
                                                      ProviderTelemetryRegistry telemetry) {
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        Objects.requireNonNull(telemetry, "telemetry");
        Comparator<LlmProvider> ranking = Comparator
                .comparingInt((LlmProvider provider) -> healthClass(telemetry.snapshot(provider)))
                .thenComparingInt(provider -> telemetry.snapshot(provider).consecutiveFailures())
                .thenComparingInt(provider -> telemetry.snapshot(provider).activeRequests())
                .thenComparingLong(provider -> latencyRank(telemetry.snapshot(provider)))
                .thenComparingInt(AdaptiveProviderRoutingPolicy::basePriority);
        return configuredProviders.stream().distinct().sorted(ranking).toList();
    }

    private static int healthClass(ProviderTelemetryRegistry.Snapshot snapshot) {
        if (snapshot.quotaExhausted()) return 2;
        if (snapshot.coolingDown()) return 1;
        return 0;
    }

    private static long latencyRank(ProviderTelemetryRegistry.Snapshot snapshot) {
        if (snapshot.totalCalls() == 0 || snapshot.ewmaLatencyMillis() < 0) return 15_000L;
        return snapshot.ewmaLatencyMillis();
    }

    private static int basePriority(LlmProvider provider) {
        return switch (provider) {
            case GOOGLE -> 0;
            case ANTHROPIC -> 1;
            case OPENAI -> 2;
        };
    }
}
