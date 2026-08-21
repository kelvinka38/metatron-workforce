package com.metatron.workforce.phase4;

import java.time.Instant;
import java.util.Objects;

/**
 * Workforce-side representation of a material organizational relationship.
 *
 * <p>This record does not create constitutional legitimacy or authorization.
 * It represents the relationship context required by Workforce and preserves
 * temporal validity and attribution.</p>
 */
public record OrganizationRelationship(
        String relationshipId,
        String sourceWorkerId,
        String targetWorkerId,
        RelationshipType type,
        String organizationContextId,
        Instant effectiveAt,
        Instant endedAt,
        String initiatedBy,
        String authorityReference,
        String evidenceReference) {

    public OrganizationRelationship {
        requireText(relationshipId, "relationshipId");
        requireText(sourceWorkerId, "sourceWorkerId");
        requireText(targetWorkerId, "targetWorkerId");
        Objects.requireNonNull(type, "type");
        requireText(organizationContextId, "organizationContextId");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        requireText(initiatedBy, "initiatedBy");
        requireText(authorityReference, "authorityReference");
        requireText(evidenceReference, "evidenceReference");

        if (sourceWorkerId.equals(targetWorkerId)) {
            throw new IllegalArgumentException("sourceWorkerId and targetWorkerId must differ");
        }
        if (endedAt != null && endedAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException("endedAt must not precede effectiveAt");
        }
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(effectiveAt)
                && (endedAt == null || instant.isBefore(endedAt));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public enum RelationshipType {
        REPORTS_TO,
        MANAGES,
        SUPERVISES,
        ADVISES,
        COORDINATES_WITH,
        DELEGATES_TO
    }
}
