package com.metatron.workforce.phase10;

import java.util.Objects;

public record LearningImprovement(
        String candidateId,
        double baselineOutput,
        double improvedOutput,
        double improvement,
        String rootCause,
        String lesson,
        boolean validated,
        String provenance) {
    public LearningImprovement {
        Objects.requireNonNull(candidateId);
        Objects.requireNonNull(rootCause);
        Objects.requireNonNull(lesson);
        Objects.requireNonNull(provenance);
        if (provenance.isBlank()) {
            throw new IllegalArgumentException("learning provenance is mandatory");
        }
        if (baselineOutput < 0 || improvedOutput < 0) {
            throw new IllegalArgumentException("performance values cannot be negative");
        }
        if (validated && improvedOutput < baselineOutput) {
            throw new IllegalArgumentException("validated improvement cannot reduce the baseline metric");
        }
    }
}
