package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

/** Provider-neutral request for scarce reasoning capacity. */
public record IntelligenceRequest(
        String requestId,
        String requester,
        IntelligenceMode mode,
        CollaborationMode collaborationMode,
        String objective,
        String context,
        List<String> evidenceReferences,
        String requiredCapability,
        String consequence,
        String latencyBudget,
        String costBudget,
        String authorityContext,
        String requiredOutput,
        List<LlmProvider> requestedProviders,
        int maxProviders,
        boolean freshExternalDataRequired,
        IntelligenceOriginContext originContext,
        CognitiveOutputBudget outputBudget) {

    /** Backward-compatible constructor predating the request-aware output-token budget. */
    public IntelligenceRequest(
            String requestId,
            String requester,
            IntelligenceMode mode,
            CollaborationMode collaborationMode,
            String objective,
            String context,
            List<String> evidenceReferences,
            String requiredCapability,
            String consequence,
            String latencyBudget,
            String costBudget,
            String authorityContext,
            String requiredOutput,
            List<LlmProvider> requestedProviders,
            int maxProviders,
            boolean freshExternalDataRequired,
            IntelligenceOriginContext originContext) {
        this(requestId, requester, mode, collaborationMode, objective, context, evidenceReferences,
                requiredCapability, consequence, latencyBudget, costBudget, authorityContext, requiredOutput,
                requestedProviders, maxProviders, freshExternalDataRequired, originContext,
                CognitiveOutputBudget.SELECTION);
    }

    /** Backward-compatible constructor for callers that do not require external freshness. */
    public IntelligenceRequest(
            String requestId,
            String requester,
            IntelligenceMode mode,
            CollaborationMode collaborationMode,
            String objective,
            String context,
            List<String> evidenceReferences,
            String requiredCapability,
            String consequence,
            String latencyBudget,
            String costBudget,
            String authorityContext,
            String requiredOutput,
            List<LlmProvider> requestedProviders,
            int maxProviders) {
        this(requestId, requester, mode, collaborationMode, objective, context, evidenceReferences,
                requiredCapability, consequence, latencyBudget, costBudget, authorityContext, requiredOutput,
                requestedProviders, maxProviders, false,
                IntelligenceOriginContext.human(requester, requestId, requiredCapability));
    }

    /** Backward-compatible constructor for callers predating typed Intelligence origin. */
    public IntelligenceRequest(
            String requestId,
            String requester,
            IntelligenceMode mode,
            CollaborationMode collaborationMode,
            String objective,
            String context,
            List<String> evidenceReferences,
            String requiredCapability,
            String consequence,
            String latencyBudget,
            String costBudget,
            String authorityContext,
            String requiredOutput,
            List<LlmProvider> requestedProviders,
            int maxProviders,
            boolean freshExternalDataRequired) {
        this(requestId, requester, mode, collaborationMode, objective, context, evidenceReferences,
                requiredCapability, consequence, latencyBudget, costBudget, authorityContext, requiredOutput,
                requestedProviders, maxProviders, freshExternalDataRequired,
                IntelligenceOriginContext.human(requester, requestId, requiredCapability));
    }

    public IntelligenceRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        Objects.requireNonNull(requiredCapability, "requiredCapability");
        Objects.requireNonNull(consequence, "consequence");
        Objects.requireNonNull(latencyBudget, "latencyBudget");
        Objects.requireNonNull(costBudget, "costBudget");
        Objects.requireNonNull(authorityContext, "authorityContext");
        Objects.requireNonNull(requiredOutput, "requiredOutput");
        Objects.requireNonNull(requestedProviders, "requestedProviders");
        Objects.requireNonNull(originContext, "originContext");
        Objects.requireNonNull(outputBudget, "outputBudget");
        evidenceReferences = List.copyOf(evidenceReferences);
        requestedProviders = List.copyOf(requestedProviders);

        if (requestId.isBlank() || requester.isBlank() || objective.isBlank()) {
            throw new IllegalArgumentException("requestId, requester and objective must not be blank");
        }
        if (!requestId.equals(originContext.requestId())) {
            throw new IllegalArgumentException("originContext.requestId must match requestId");
        }
        if (requiredCapability.isBlank() || requiredOutput.isBlank()) {
            throw new IllegalArgumentException("requiredCapability and requiredOutput must not be blank");
        }
        if (maxProviders < 1) throw new IllegalArgumentException("maxProviders must be >= 1");
        if (collaborationMode != CollaborationMode.SINGLE && maxProviders < 2) {
            throw new IllegalArgumentException("multi-provider collaboration requires maxProviders>=2");
        }
        if (requestedProviders.size() > maxProviders) {
            throw new IllegalArgumentException("requested providers exceed maxProviders");
        }
    }
}
