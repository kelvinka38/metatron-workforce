package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic routing policy over a shared provider-capacity snapshot. */
public final class CapacityAwareRoutingPolicy implements IntelligenceRoutingPolicy {
    private final Map<LlmProvider, ProviderCapacity> capacity;

    public CapacityAwareRoutingPolicy(List<ProviderCapacity> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        EnumMap<LlmProvider, ProviderCapacity> map = new EnumMap<>(LlmProvider.class);
        for (ProviderCapacity entry : snapshot) {
            Objects.requireNonNull(entry, "capacity entry");
            if (map.put(entry.provider(), entry) != null) {
                throw new IllegalArgumentException("duplicate provider capacity: " + entry.provider());
            }
        }
        this.capacity = Map.copyOf(map);
    }

    @Override
    public List<LlmProvider> select(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");

        if (!request.requestedProviders().isEmpty()) {
            return request.requestedProviders().stream()
                    .filter(this::isUsable)
                    .limit(request.maxProviders())
                    .toList();
        }

        Comparator<ProviderCapacity> ranking = Comparator
                .comparingInt(ProviderCapacity::priority).reversed()
                .thenComparingLong(ProviderCapacity::estimatedLatencyMillis)
                .thenComparingLong(ProviderCapacity::estimatedCostMicros)
                .thenComparing(entry -> entry.provider().name());

        return capacity.values().stream()
                .filter(entry -> isUsable(entry.provider()))
                .sorted(ranking)
                .limit(request.maxProviders())
                .map(ProviderCapacity::provider)
                .toList();
    }

    private boolean isUsable(LlmProvider provider) {
        ProviderCapacity entry = capacity.get(provider);
        return entry != null
                && entry.available()
                && entry.availableConcurrency() > 0
                && entry.availableTokens() > 0;
    }
}
