package com.metatron.workforce.phase8;

import java.time.Instant;

public record Adoption(
        String adoptionId,
        String candidateId,
        String validationId,
        String decisionMakerId,
        Instant adoptedAt) {

    public Adoption {
        requireText(adoptionId, "adoptionId");
        requireText(candidateId, "candidateId");
        requireText(validationId, "validationId");
        requireText(decisionMakerId, "decisionMakerId");
        if (adoptedAt == null) throw new IllegalArgumentException("adoptedAt must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
