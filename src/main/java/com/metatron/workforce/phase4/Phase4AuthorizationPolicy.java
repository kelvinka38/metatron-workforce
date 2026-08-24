package com.metatron.workforce.phase4;

import java.time.Instant;

@FunctionalInterface
public interface Phase4AuthorizationPolicy {
    AuthorizationDecision authorize(String actorId, String targetId, String action, String scope,
                                    String organizationContextId, Instant at);

    record AuthorizationDecision(boolean allowed, String authorizationReference, String reason) {
        public AuthorizationDecision {
            if (authorizationReference == null || authorizationReference.isBlank()) {
                throw new IllegalArgumentException("authorizationReference must not be blank");
            }
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }

        public static AuthorizationDecision allowed(String reference) {
            return new AuthorizationDecision(true, reference, "allowed");
        }

        public static AuthorizationDecision denied(String reference, String reason) {
            return new AuthorizationDecision(false, reference, reason);
        }
    }
}
