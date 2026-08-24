package com.metatron.workforce.phase6;

import java.time.Instant;

public record AuthorizationRequest(
        String actorId,
        String roleId,
        String authorityId,
        String scope,
        String action,
        String contextId,
        Instant at,
        String resourceId) {
    public AuthorizationRequest {
        require(actorId, "actorId"); require(roleId, "roleId"); require(authorityId, "authorityId");
        require(scope, "scope"); require(action, "action"); require(contextId, "contextId"); require(resourceId, "resourceId");
        if (at == null) throw new IllegalArgumentException("at must not be null");
    }

    /** Backward-compatible aliases retained for historical acceptance evidence. */
    public String requestId() { return resourceId; }
    public String organizationContextId() { return contextId; }

    /**
     * Compatibility constructor for the pre-Phase-6 authorization surface.
     * The canonical Phase-6 model remains the 8-field record above.
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
                requestedAt, evidenceReference);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
