package com.metatron.workforce.phase6;

import java.time.Instant;

@FunctionalInterface
public interface Phase6AuthorizationPolicy {
    Decision authorize(AuthorizationRequest request);

    record Decision(boolean allowed, String authorizationReference, String reason) {
        public Decision {
            if (authorizationReference == null || authorizationReference.isBlank()) throw new IllegalArgumentException("authorizationReference must not be blank");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }
        public static Decision allowed(String reference) { return new Decision(true, reference, "allowed"); }
        public static Decision denied(String reference, String reason) { return new Decision(false, reference, reason); }
    }
}
