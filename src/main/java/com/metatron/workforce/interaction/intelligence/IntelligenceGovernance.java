package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;

/** Boundary to BIOS/evidence/authority governance; no vendor-specific logic belongs here. */
@FunctionalInterface
public interface IntelligenceGovernance {
    void validate(IntelligenceRequest request, List<LlmResponse> responses);
}
