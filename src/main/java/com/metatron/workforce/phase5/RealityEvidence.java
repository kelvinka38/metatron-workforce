package com.metatron.workforce.phase5;

import java.time.Instant;
import java.util.Objects;

public record RealityEvidence(
        String subjectId,
        Instant effectiveAt,
        Instant expiresAt,
        Instant recordedAt,
        String actorId,
        String contextId,
        String sourceRef) {

    public RealityEvidence {
        requireText("subjectId", subjectId);
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (expiresAt != null && !expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("expiresAt must be after effectiveAt");
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
        requireText("actorId", actorId);
        requireText("contextId", contextId);
        requireText("sourceRef", sourceRef);
    }

    public boolean validAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(effectiveAt) && (expiresAt == null || instant.isBefore(expiresAt));
    }

    private static void requireText(String name, String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
