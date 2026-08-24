package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Optional;

public record AuthorizationRequest(
        String actorId,
        String roleId,
        String authorityId,
        String scope,
        String action,
        String contextId,
        Instant at,
        String resourceId,
        String legacyRequestId,
        String legacyDelegationId,
        String legacyPolicyId,
        Instant legacyEffectiveAt,
        Instant legacyExpiresAt) {

    public AuthorizationRequest {
        require(actorId, "actorId"); require(roleId, "roleId"); require(authorityId, "authorityId");
        require(scope, "scope"); require(action, "action"); require(contextId, "contextId"); require(resourceId, "resourceId");
        if (at == null) throw new IllegalArgumentException("at must not be null");
    }

    /** Canonical Phase-6 constructor. */
    public AuthorizationRequest(
            String actorId,
            String roleId,
            String authorityId,
            String scope,
            String action,
            String contextId,
            Instant at,
            String resourceId) {
        this(actorId, roleId, authorityId, scope, action, contextId, at, resourceId,
                resourceId, null, null, at, null);
    }

    /** Backward-compatible aliases retained for historical acceptance evidence. */
    public String requestId() { return legacyRequestId; }
    public String organizationContextId() { return contextId; }
    public Optional<String> delegationIdIfLegacy() { return Optional.ofNullable(legacyDelegationId); }
    public Optional<String> policyIdIfLegacy() { return Optional.ofNullable(legacyPolicyId); }
    public Optional<Instant> effectiveAtIfLegacy() { return Optional.ofNullable(legacyEffectiveAt); }
    public Optional<Instant> expiresAtIfLegacy() { return Optional.ofNullable(legacyExpiresAt); }

    /**
     * Compatibility constructor for the pre-Phase-6 authorization surface.
     * The canonical Phase-6 model remains the 8-field constructor above.
     */
    @Deprecated
    public AuthorizationRequest(
            String requestId,
            String actorId,
            String roleId,
            String action,
            String scope,
            String organizationContextId,
            String authorizationId,
            String delegationId,
            String policyId,
            Instant requestedAt,
            Instant effectiveAt,
            Instant expiresAt,
            String evidenceReference) {
        this(actorId, roleId, authorizationId, scope, action, organizationContextId,
                requestedAt, evidenceReference, requestId, delegationId, policyId, effectiveAt, expiresAt);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
