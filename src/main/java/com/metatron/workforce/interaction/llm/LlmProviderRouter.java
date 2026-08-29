package com.metatron.workforce.interaction.llm;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Routes a request to an explicitly selected provider and records live provider telemetry. */
public final class LlmProviderRouter {
    private final Map<LlmProvider, LlmProviderClient> clients;
    private final ProviderTelemetryRegistry telemetry;

    public LlmProviderRouter(List<LlmProviderClient> clients) {
        this(clients, new ProviderTelemetryRegistry());
    }

    public LlmProviderRouter(List<LlmProviderClient> clients, ProviderTelemetryRegistry telemetry) {
        Objects.requireNonNull(clients, "clients");
        EnumMap<LlmProvider, LlmProviderClient> map = new EnumMap<>(LlmProvider.class);
        for (LlmProviderClient client : clients) {
            Objects.requireNonNull(client, "client");
            if (map.put(client.provider(), client) != null) {
                throw new IllegalArgumentException("duplicate LLM provider: " + client.provider());
            }
        }
        this.clients = Map.copyOf(map);
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        LlmProviderClient client = clients.get(request.provider());
        if (client == null) {
            throw new IllegalStateException("LLM provider is not configured: " + request.provider());
        }
        long started = telemetry.begin(request.provider());
        try {
            LlmResponse response = client.complete(request);
            telemetry.success(request.provider(), started, response);
            return response;
        } catch (RuntimeException failure) {
            telemetry.failure(request.provider(), started, failure);
            throw failure;
        }
    }

    public ProviderTelemetryRegistry telemetry() {
        return telemetry;
    }
}
