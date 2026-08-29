package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Routing policy for runtimes that know only which provider transports are configured.
 *
 * Configuration presence is not live capacity, quota, latency, or cost telemetry. This policy
 * therefore preserves configured priority order without manufacturing ProviderCapacity data.
 */
public final class ConfiguredProviderRoutingPolicy implements IntelligenceRoutingPolicy {
    private final List<LlmProvider> configuredProviders;

    public ConfiguredProviderRoutingPolicy(List<LlmProvider> configuredProviders) {
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        Set<LlmProvider> distinct = new HashSet<>();
        for (LlmProvider provider : configuredProviders) {
            Objects.requireNonNull(provider, "configured provider");
            if (!distinct.add(provider)) {
                throw new IllegalArgumentException("duplicate configured provider: " + provider);
            }
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
        return configuredProviders.stream()
                .limit(request.maxProviders())
                .toList();
    }
}
