package com.metatron.workforce.phase8;

import java.time.Instant;

public record FutureBehavior(
        String behaviorId,
        String adoptionId,
        String behavior,
        Instant effectiveAt) {

    public FutureBehavior {
        requireText(behaviorId, "behaviorId");
        requireText(adoptionId, "adoptionId");
        requireText(behavior, "behavior");
        if (effectiveAt == null) throw new IllegalArgumentException("effectiveAt must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
