package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Adapts the low-level provider router into the shared Intelligence Fabric. */
public final class RouterBackedIntelligenceEngine implements IntelligenceEngine {
    private final LlmProviderRouter router;
    private final Function<LlmProvider, String> modelSelector;

    public RouterBackedIntelligenceEngine(
            LlmProviderRouter router,
            Function<LlmProvider, String> modelSelector) {
        this.router = Objects.requireNonNull(router, "router");
        this.modelSelector = Objects.requireNonNull(modelSelector, "modelSelector");
    }

    @Override
    public LlmResponse execute(LlmProvider provider, IntelligenceRequest request) {
        return execute(provider, request, null, defaultBudget(request));
    }

    @Override
    public LlmResponse execute(LlmProvider provider,
                               IntelligenceRequest request,
                               EscalationReason escalationReason) {
        return execute(provider, request, escalationReason, defaultBudget(request));
    }

    @Override
    public LlmResponse execute(LlmProvider provider,
                               IntelligenceRequest request,
                               EscalationReason escalationReason,
                               ProviderBudget providerBudget) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(providerBudget, "providerBudget");
        String model = Objects.requireNonNull(modelSelector.apply(provider), "selected model");
        if (model.isBlank()) throw new IllegalArgumentException("selected model must not be blank");

        return router.complete(new LlmRequest(
                provider,
                model,
                systemContext(request),
                request.objective(),
                rootLogicalRequestRef(request.requestId()),
                caseRef(request.context()),
                purpose(request.requiredCapability()),
                escalationReason == null ? "" : escalationReason.name(),
                providerBudget.toFrontierCallBudget()));
    }

    private static ProviderBudget defaultBudget(IntelligenceRequest request) {
        ProviderBudget budget = ProviderBudget.forDepth(depthFrom(request));
        if (request.collaborationMode() != CollaborationMode.SINGLE) {
            budget = budget.withExplicitMultiModelRequest(request.maxProviders());
        }
        return budget;
    }

    private static IntelligenceDepth depthFrom(IntelligenceRequest request) {
        String latency = request.latencyBudget() == null ? "" : request.latencyBudget().toLowerCase(Locale.ROOT);
        String cost = request.costBudget() == null ? "" : request.costBudget().toLowerCase(Locale.ROOT);
        if (latency.contains("extended") || cost.contains("deep")) return IntelligenceDepth.DEEP;
        if (latency.contains("analysis") || cost.contains("expanded")) return IntelligenceDepth.ANALYZE;
        return IntelligenceDepth.FAST;
    }

    private static String purpose(String capability) {
        String value = capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT);
        if ("deliberation-normalization".equals(value)) return "multi-model-normalization";
        if ("targeted-challenge".equals(value)) return "multi-model-challenge";
        if (value.contains("execution")) return "execution-planning-or-reasoning";
        if (value.contains("worker.intelligence")) return "worker-intelligence";
        return value.isBlank() ? "intelligence-reasoning" : "intelligence-reasoning:" + value;
    }

    private static String rootLogicalRequestRef(String requestId) {
        if (requestId == null || requestId.isBlank()) return "";
        String value = requestId.trim();
        String normalizePrefix = "deliberation-normalize-";
        if (value.startsWith(normalizePrefix)) return value.substring(normalizePrefix.length());
        String challengePrefix = "deliberation-challenge-";
        if (value.startsWith(challengePrefix)) {
            String remainder = value.substring(challengePrefix.length());
            for (LlmProvider provider : LlmProvider.values()) {
                String suffix = "-" + provider.name();
                if (remainder.endsWith(suffix) && remainder.length() > suffix.length()) {
                    return remainder.substring(0, remainder.length() - suffix.length());
                }
            }
            return remainder;
        }
        return value;
    }

    private static String caseRef(String context) {
        if (context == null || context.isBlank()) return "";
        for (String line : context.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("case_id=")) {
                return trimmed.substring("case_id=".length()).trim();
            }
        }
        return "";
    }

    private static String systemContext(IntelligenceRequest request) {
        return "METATRON INTELLIGENCE REQUEST\n"
                + "requester=" + request.requester() + "\n"
                + "mode=" + request.mode() + "\n"
                + "requiredCapability=" + request.requiredCapability() + "\n"
                + "consequence=" + request.consequence() + "\n"
                + "authorityContext=" + request.authorityContext() + "\n"
                + "context=" + request.context() + "\n"
                + "evidenceReferences=" + request.evidenceReferences();
    }
}
