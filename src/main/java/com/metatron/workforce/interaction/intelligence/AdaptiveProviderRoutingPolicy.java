package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.ProviderTelemetryRegistry;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral AUTO routing over live measured provider health/capacity/cost telemetry plus
 * evidence-backed capability quality. Explicit Human provider selection is preserved exactly;
 * AUTO is the only mode allowed to adapt provider ordering.
 */
public final class AdaptiveProviderRoutingPolicy implements IntelligenceRoutingPolicy {
    private final List<LlmProvider> configuredProviders;
    private final ProviderTelemetryRegistry telemetry;
    private final ProviderCapabilityQualityRegistry quality;

    public AdaptiveProviderRoutingPolicy(List<LlmProvider> configuredProviders,
                                         ProviderTelemetryRegistry telemetry) {
        this(configuredProviders, telemetry, new ProviderCapabilityQualityRegistry());
    }

    public AdaptiveProviderRoutingPolicy(List<LlmProvider> configuredProviders,
                                         ProviderTelemetryRegistry telemetry,
                                         ProviderCapabilityQualityRegistry quality) {
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.quality = Objects.requireNonNull(quality, "quality");
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
            List<LlmProvider> unavailable = request.requestedProviders().stream()
                    .filter(provider -> !configuredProviders.contains(provider)).toList();
            if (!unavailable.isEmpty()) {
                throw new IllegalStateException("explicit intelligence provider is not configured: " + unavailable);
            }
            return request.requestedProviders().stream().limit(request.maxProviders()).toList();
        }
        return rankForRequest(configuredProviders, telemetry, quality, request).stream()
                .limit(request.maxProviders())
                .toList();
    }

    static List<LlmProvider> rankForRequest(List<LlmProvider> configuredProviders,
                                            ProviderTelemetryRegistry telemetry,
                                            ProviderCapabilityQualityRegistry quality,
                                            IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        boolean latencySensitive = latencySensitive(request.latencyBudget());
        boolean costSensitive = costSensitive(request.costBudget());

        Comparator<LlmProvider> ranking = Comparator
                .comparingInt((LlmProvider provider) -> healthClass(telemetry.snapshot(provider)))
                .thenComparingInt(provider -> telemetry.snapshot(provider).consecutiveFailures())
                .thenComparingDouble(provider -> -quality.snapshot(provider, request.requiredCapability()).score());

        if (latencySensitive) {
            ranking = ranking.thenComparingLong(provider -> latencyRank(telemetry.snapshot(provider)));
        }
        if (costSensitive) {
            ranking = ranking
                    .thenComparingInt(provider -> costKnownRank(telemetry.snapshot(provider)))
                    .thenComparingLong(provider -> costRank(telemetry.snapshot(provider)));
        }

        ranking = ranking
                .thenComparingInt(provider -> telemetry.snapshot(provider).activeRequests())
                .thenComparingLong(provider -> latencyRank(telemetry.snapshot(provider)))
                .thenComparingInt(AdaptiveProviderRoutingPolicy::basePriority);

        return configuredProviders.stream().distinct().sorted(ranking).toList();
    }

    /**
     * Shared health ordering for frontier boundaries that do not yet carry a full IntelligenceRequest,
     * such as semantic normalization. It intentionally does not invent capability or cost preferences.
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

    private static int costKnownRank(ProviderTelemetryRegistry.Snapshot snapshot) {
        return snapshot.costKnown() && snapshot.successes() > 0 ? 0 : 1;
    }

    private static long costRank(ProviderTelemetryRegistry.Snapshot snapshot) {
        if (!snapshot.costKnown() || snapshot.successes() <= 0) return Long.MAX_VALUE / 4;
        return Math.max(0L, snapshot.totalEstimatedCostMicros() / snapshot.successes());
    }

    private static boolean latencySensitive(String budget) {
        String value = budget == null ? "" : budget.toLowerCase(Locale.ROOT);
        return value.contains("fast") || value.contains("interactive") || value.contains("low-latency")
                || value.contains("realtime") || value.contains("real-time");
    }

    private static boolean costSensitive(String budget) {
        String value = budget == null ? "" : budget.toLowerCase(Locale.ROOT);
        return value.contains("cheap") || value.contains("economy") || value.contains("low-cost")
                || value.contains("tight") || value.contains("conserve") || value.contains("minimal-cost");
    }

    private static int basePriority(LlmProvider provider) {
        return switch (provider) {
            case GOOGLE -> 0;
            case ANTHROPIC -> 1;
            case OPENAI -> 2;
            case OLLAMA -> 3;
        };
    }

}
