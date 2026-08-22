package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;

/** Immutable attribution link for a material institutional action. */
public record AttributionRecord(
        String attributionId,
        String originatingActorId,
        String decisionActorId,
        String authorizingActorId,
        String responsibleWorkerId,
        String runtimeInstanceId,
        String authorizationId,
        String assignmentId,
        String executionId,
        String action,
        Instant occurredAt,
        Instant recordedAt,
        String evidenceReference,
        String provenanceReference) {

    public AttributionRecord {
        requireText(attributionId, "attributionId");
        requireText(originatingActorId, "originatingActorId");
        requireText(action, "action");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        requireText(evidenceReference, "evidenceReference");
        requireText(provenanceReference, "provenanceReference");
    }

    public boolean recordingLagExists() {
        return recordedAt.isAfter(occurredAt);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
