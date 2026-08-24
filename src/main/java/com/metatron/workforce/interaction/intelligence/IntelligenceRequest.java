package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.List;
import java.util.Objects;

/** Provider-neutral request for scarce reasoning capacity. */
public record IntelligenceRequest(
        String requestId,
        IntelligenceMode mode,
        CollaborationMode collaborationMode,
        String task,
        String context,
        List<LlmProvider> requestedProviders,
        int maxProviders) {

    public IntelligenceRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(requestedProviders, "requestedProviders");
        requestedProviders = List.copyOf(requestedProviders);
        if (requestId.isBlank() || task.isBlank()) throw new IllegalArgumentException("requestId and task must not be blank");
        if (maxProviders < 1) throw new IllegalArgumentException("maxProviders must be >= 1");
        if (collaborationMode == CollaborationMode.SINGLE && maxProviders != 1) {
            throw new IllegalArgumentException("SINGLE collaboration requires maxProviders=1");
        }
        if (collaborationMode != CollaborationMode.SINGLE && maxProviders < 2) {
            throw new IllegalArgumentException("multi-provider collaboration requires maxProviders>=2");
        }
        if (requestedProviders.size() > maxProviders) {
            throw new IllegalArgumentException("requested providers exceed maxProviders");
        }
    }
}
