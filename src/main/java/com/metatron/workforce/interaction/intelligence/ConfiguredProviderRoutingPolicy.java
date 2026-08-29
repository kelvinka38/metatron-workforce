package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Routing policy for runtimes that know which provider transports are configured.
 *
 * Explicit provider requests are always respected. AUTO routing is provider-agnostic
 * and uses an operational preference order chosen for the current Workforce runtime:
 * Google first, Anthropic second, OpenAI third. IntelligenceFabric will continue to
 * the next configured provider when the preferred provider fails, so one provider's
 * quota or outage cannot make the interactive agent unavailable.
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
                .sorted(Comparator.comparingInt(ConfiguredProviderRoutingPolicy::autoPriority))
                .limit(request.maxProviders())
                .toList();
    }

    private static int autoPriority(LlmProvider provider) {
        return switch (provider) {
            case GOOGLE -> 0;
            case ANTHROPIC -> 1;
            case OPENAI -> 2;
        };
    }
}
