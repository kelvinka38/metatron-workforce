package com.metatron.workforce.interaction.llm;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Routes a request to an explicitly selected provider and records live provider telemetry. */
public final class LlmProviderRouter {
    private final Map<LlmProvider, LlmProviderClient> clients;
    private final ProviderTelemetryRegistry telemetry;
    private final ProviderCallTraceRegistry callTrace;
    private final ProviderCallBudgetRegistry callBudgetRegistry;

    public LlmProviderRouter(List<LlmProviderClient> clients) {
        this(clients, new ProviderTelemetryRegistry(), new ProviderCallTraceRegistry(),
                new ProviderCallBudgetRegistry());
    }

    public LlmProviderRouter(List<LlmProviderClient> clients, ProviderTelemetryRegistry telemetry) {
        this(clients, telemetry, new ProviderCallTraceRegistry(), new ProviderCallBudgetRegistry());
    }

    public LlmProviderRouter(List<LlmProviderClient> clients,
                             ProviderTelemetryRegistry telemetry,
                             ProviderCallTraceRegistry callTrace) {
        this(clients, telemetry, callTrace, new ProviderCallBudgetRegistry());
    }

    public LlmProviderRouter(List<LlmProviderClient> clients,
                             ProviderTelemetryRegistry telemetry,
                             ProviderCallTraceRegistry callTrace,
                             ProviderCallBudgetRegistry callBudgetRegistry) {
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
        this.callTrace = Objects.requireNonNull(callTrace, "callTrace");
        this.callBudgetRegistry = Objects.requireNonNull(callBudgetRegistry, "callBudgetRegistry");
    }

    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        LlmRequest effective = applyInstitutionalCallContext(request);
        LlmProviderClient client = clients.get(effective.provider());
        if (client == null) {
            throw new IllegalStateException("LLM provider is not configured: " + effective.provider());
        }

        // Hard cognitive-capacity admission happens before transport invocation. A failed provider
        // request still consumes the authorized call because capacity/credits were attempted.
        callBudgetRegistry.authorize(effective);

        long started = telemetry.begin(effective.provider());
        long callStarted = System.nanoTime();
        try {
            LlmResponse response = client.complete(effective);
            telemetry.success(effective.provider(), started, response);
            callTrace.success(effective, callStarted, response);
            return response;
        } catch (RuntimeException failure) {
            telemetry.failure(effective.provider(), started, failure);
            callTrace.failure(effective, callStarted, failure);
            throw failure;
        }
    }

    private static LlmRequest applyInstitutionalCallContext(LlmRequest request) {
        String scopedLogicalRef = LlmCallContext.logicalRequestRef();
        String logicalRef = scopedLogicalRef.isBlank() ? request.logicalRequestRef() : scopedLogicalRef;

        String prefix = LlmCallContext.systemContextPrefix();
        String systemContext = request.systemContext();
        if (!prefix.isBlank() && !systemContext.contains("institutional_context_fingerprint=")) {
            systemContext = prefix + "\n\n" + systemContext;
        }

        FrontierCallBudget budget = request.callBudget();
        FrontierCallBudget scopedBudget = LlmCallContext.defaultBudget();
        if (!budget.enforced() && scopedBudget != null) budget = scopedBudget;

        if (Objects.equals(logicalRef, request.logicalRequestRef())
                && Objects.equals(systemContext, request.systemContext())
                && budget == request.callBudget()) {
            return request;
        }
        return new LlmRequest(
                request.provider(), request.model(), systemContext, request.userInput(),
                logicalRef, request.caseRef(), request.purpose(), request.reasonCode(), budget);
    }

    public ProviderTelemetryRegistry telemetry() {
        return telemetry;
    }

    public ProviderCallTraceRegistry callTrace() {
        return callTrace;
    }

    public ProviderCallBudgetRegistry callBudgetRegistry() {
        return callBudgetRegistry;
    }
}
