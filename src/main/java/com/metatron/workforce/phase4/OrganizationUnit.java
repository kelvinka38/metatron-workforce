package com.metatron.workforce.phase4;

import java.time.Instant;
import java.util.Objects;

/** Workforce-side organizational context; does not establish external legitimacy. */
public record OrganizationUnit(
        String unitId,
        String parentUnitId,
        UnitType type,
        String name,
        Instant effectiveAt,
        Instant endedAt,
        String initiatedBy,
        String authorityReference,
        String evidenceReference) {

    public OrganizationUnit {
        requireText(unitId, "unitId");
        Objects.requireNonNull(type, "type");
        requireText(name, "name");
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

    public enum UnitType { INSTITUTION, ORGANIZATION, DEPARTMENT, TEAM, PROJECT }
}
