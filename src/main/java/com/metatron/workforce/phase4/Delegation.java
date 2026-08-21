package com.metatron.workforce.phase4;

import java.time.Instant;
import java.util.Objects;

/**
 * Workforce-side representation of a bounded delegation.
 *
 * <p>A delegation does not manufacture legitimacy. The delegator must already
 * possess the referenced authority, and the delegation remains bounded by
 * scope, context, time, and policy/conditions.</p>
 */
public record Delegation(
        String delegationId,
        String delegatorWorkerId,
        String delegateeWorkerId,
        String authorityReference,
        String scope,
        String organizationContextId,
        Instant effectiveAt,
        Instant expiresAt,
        String initiatedBy,
        String evidenceReference) {

    public Delegation {
        requireText(delegationId, "delegationId");
        requireText(delegatorWorkerId, "delegatorWorkerId");
        requireText(delegateeWorkerId, "delegateeWorkerId");
        requireText(authorityReference, "authorityReference");
        requireText(scope, "scope");
        requireText(organizationContextId, "organizationContextId");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        requireText(initiatedBy, "initiatedBy");
        requireText(evidenceReference, "evidenceReference");

        if (delegatorWorkerId.equals(delegateeWorkerId)) {
            throw new IllegalArgumentException("delegatorWorkerId and delegateeWorkerId must differ");
        }
        if (expiresAt != null && expiresAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException("expiresAt must not precede effectiveAt");
        }
    }

    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(effectiveAt)
                && (expiresAt == null || instant.isBefore(expiresAt));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
