package com.metatron.workforce.phase8;

import java.time.Instant;
import java.util.Objects;

public record LearningEvidence(
        String evidenceId,
        String executionId,
        String observationId,
        String outcomeId,
        String sourceReference,
        Instant observedAt,
        StatementNature nature) {

    public enum StatementNature { OBSERVED, INFERRED, ESTIMATED }

    public LearningEvidence {
        requireText(evidenceId, "evidenceId");
        requireText(executionId, "executionId");
        requireText(observationId, "observationId");
        requireText(outcomeId, "outcomeId");
        requireText(sourceReference, "sourceReference");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(nature, "nature");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
