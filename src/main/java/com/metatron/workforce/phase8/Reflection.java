package com.metatron.workforce.phase8;

import java.time.Instant;

public record Reflection(
        String reflectionId,
        String experienceId,
        String statement,
        Instant createdAt) {

    public Reflection {
        requireText(reflectionId, "reflectionId");
        requireText(experienceId, "experienceId");
        requireText(statement, "statement");
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
