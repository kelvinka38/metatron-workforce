package com.metatron.workforce.phase4;

import java.time.Instant;
import java.util.Objects;

/** A bounded institutional placement; distinct from Worker and Role. */
public record Position(
        String positionId,
        String organizationUnitId,
        String roleId,
        String workerId,
        Instant effectiveAt,
        Instant endedAt,
        String initiatedBy,
        String authorityReference,
        String evidenceReference) {

    public Position {
        requireText(positionId, "positionId");
        requireText(organizationUnitId, "organizationUnitId");
        requireText(roleId, "roleId");
        requireText(workerId, "workerId");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        requireText(initiatedBy, "initiatedBy");
        requireText(authorityReference, "authorityReference");
        requireText(evidenceReference, "evidenceReference");
        if (endedAt != null && endedAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException("endedAt must not precede effectiveAt");
        }
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(effectiveAt) && (endedAt == null || instant.isBefore(endedAt));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
