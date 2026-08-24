package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Objects;

/** Minimal evidence gate for governed reasoning; richer BIOS rules remain upstream. */
public final class EvidenceBackedGovernance implements IntelligenceGovernance {
    @Override
    public void validate(IntelligenceRequest request, List<LlmResponse> responses, String finalText) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responses, "responses");
        Objects.requireNonNull(finalText, "finalText");
        if (responses.isEmpty()) throw new IllegalStateException("no intelligence response");
        if (finalText.isBlank()) throw new IllegalStateException("empty governed result");
        if (request.mode().requiresGovernance() && request.evidenceReferences().isEmpty()) {
            throw new IllegalStateException("governed reasoning requires evidence references");
        }
    }
}
