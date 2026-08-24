package com.metatron.workforce.interaction.llm;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Routes a request to an explicitly selected provider. No implicit provider fallback is allowed. */
public final class LlmProviderRouter {
    private final Map<LlmProvider, LlmProviderClient> clients;

    public LlmProviderRouter(List<LlmProviderClient> clients) {
        Objects.requireNonNull(clients, "clients");
        EnumMap<LlmProvider, LlmProviderClient> map = new EnumMap<>(LlmProvider.class);
        for (LlmProviderClient client : clients) {
            Objects.requireNonNull(client, "client");
            if (map.put(client.provider(), client) != null) {
                throw new IllegalArgumentException("duplicate LLM provider: " + client.provider());
            }
        }
        this.clients = Map.copyOf(map);
    }

    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        LlmProviderClient client = clients.get(request.provider());
        if (client == null) {
            throw new IllegalStateException("LLM provider is not configured: " + request.provider());
        }
        return client.complete(request);
    }
}
