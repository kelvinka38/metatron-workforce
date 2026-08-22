package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;

/** Attributable result of resolving a material authorization request. */
public record AuthorizationDecision(
        String authorizationId,
        String requestId,
        Outcome outcome,
        String reason,
        String authorityReference,
        String delegationReference,
        String policyReference,
        Instant effectiveAt,
        Instant expiresAt,
        Instant decidedAt,
        String decidedBy,
        String evidenceReference) {

    public enum Outcome { ALLOW, DENY, REVIEW, DEFER }

    public AuthorizationDecision {
        requireText(authorizationId, "authorizationId");
        requireText(requestId, "requestId");
        Objects.requireNonNull(outcome, "outcome");
        requireText(reason, "reason");
        requireText(authorityReference, "authorityReference");
        requireText(policyReference, "policyReference");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(decidedAt, "decidedAt");
        requireText(decidedBy, "decidedBy");
        requireText(evidenceReference, "evidenceReference");
        if (expiresAt != null && expiresAt.isBefore(effectiveAt)) {
            throw new IllegalArgumentException("expiresAt must not precede effectiveAt");
        }
    }

    public boolean usableAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return outcome == Outcome.ALLOW
                && !instant.isBefore(effectiveAt)
                && (expiresAt == null || instant.isBefore(expiresAt));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
