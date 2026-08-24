package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/** Grounded context assembled from knowledge and tool evidence before reasoning. */
public record IntelligenceContext(
        String objective,
        String knowledge,
        List<String> evidenceReferences,
        String toolObservations) {
    public IntelligenceContext {
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(knowledge, "knowledge");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        Objects.requireNonNull(toolObservations, "toolObservations");
        evidenceReferences = List.copyOf(evidenceReferences);
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
    }
}
