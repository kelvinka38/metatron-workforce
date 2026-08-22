package com.metatron.workforce.phase8;

import java.time.Instant;

public record Validation(
        String validationId,
        String candidateId,
        String evidenceId,
        boolean passed,
        String validatorId,
        Instant validatedAt) {

    public Validation {
        requireText(validationId, "validationId");
        requireText(candidateId, "candidateId");
        requireText(evidenceId, "evidenceId");
        requireText(validatorId, "validatorId");
        if (validatedAt == null) throw new IllegalArgumentException("validatedAt must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
