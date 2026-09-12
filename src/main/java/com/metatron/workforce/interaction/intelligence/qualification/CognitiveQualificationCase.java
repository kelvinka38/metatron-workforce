package com.metatron.workforce.interaction.intelligence.qualification;

import java.util.List;
import java.util.Objects;

/** One frozen real-workload qualification case. */
public record CognitiveQualificationCase(
        String caseId,
        CognitiveQualificationCategory category,
        boolean critical,
        String capability,
        String objective,
        String context,
        List<String> acceptanceCriteria,
        List<String> forbiddenOutcomes) {

    public CognitiveQualificationCase {
        caseId = require(caseId, "caseId");
        Objects.requireNonNull(category, "category");
        capability = require(capability, "capability");
        objective = require(objective, "objective");
        context = require(context, "context");
        acceptanceCriteria = List.copyOf(Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria"));
        forbiddenOutcomes = List.copyOf(Objects.requireNonNull(forbiddenOutcomes, "forbiddenOutcomes"));
        if (acceptanceCriteria.isEmpty()) throw new IllegalArgumentException("acceptanceCriteria must not be empty");
        if (forbiddenOutcomes.isEmpty()) throw new IllegalArgumentException("forbiddenOutcomes must not be empty");
    }

    private static String require(String value, String field) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return cleaned;
    }
}
