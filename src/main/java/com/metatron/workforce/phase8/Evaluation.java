package com.metatron.workforce.phase8;

import java.time.Instant;

public record Evaluation(
        String evaluationId,
        String reflectionId,
        String conclusion,
        Instant evaluatedAt) {

    public Evaluation {
        requireText(evaluationId, "evaluationId");
        requireText(reflectionId, "reflectionId");
        requireText(conclusion, "conclusion");
        if (evaluatedAt == null) throw new IllegalArgumentException("evaluatedAt must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
