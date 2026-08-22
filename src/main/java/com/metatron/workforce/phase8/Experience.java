package com.metatron.workforce.phase8;

import java.time.Instant;

public record Experience(
        String experienceId,
        String executionId,
        String evidenceId,
        String statement,
        Instant createdAt) {

    public Experience {
        requireText(experienceId, "experienceId");
        requireText(executionId, "executionId");
        requireText(evidenceId, "evidenceId");
        requireText(statement, "statement");
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
