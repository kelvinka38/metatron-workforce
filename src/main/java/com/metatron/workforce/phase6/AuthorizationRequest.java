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
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
