package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;

/** Material authorization request evaluated before execution admission. */
public record AuthorizationRequest(
        String requestId,
        String actorId,
        String roleContext,
        String action,
        String scope,
        String organizationContextId,
        String authorityReference,
        String delegationReference,
        String policyReference,
        Instant requestedAt,
        Instant validFrom,
        Instant validUntil,
        String evidenceReference) {

    public AuthorizationRequest {
        requireText(requestId, "requestId");
        requireText(actorId, "actorId");
        requireText(roleContext, "roleContext");
        requireText(action, "action");
        requireText(scope, "scope");
        requireText(organizationContextId, "organizationContextId");
        requireText(authorityReference, "authorityReference");
        requireText(policyReference, "policyReference");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(validFrom, "validFrom");
        requireText(evidenceReference, "evidenceReference");
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil must not precede validFrom");
        }
    }

    public boolean validAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(validFrom)
                && (validUntil == null || instant.isBefore(validUntil));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
