package com.metatron.workforce.phase3;

import java.util.Objects;

public record AuthorizationContext(String authorizationId, boolean allowed) {
    public AuthorizationContext {
        Objects.requireNonNull(authorizationId, "authorizationId");
        if (authorizationId.isBlank()) throw new IllegalArgumentException("authorizationId must not be blank");
    }

    public static AuthorizationContext denied(String authorizationId) {
        return new AuthorizationContext(authorizationId, false);
    }

    public static AuthorizationContext allowed(String authorizationId) {
        return new AuthorizationContext(authorizationId, true);
    }
}
