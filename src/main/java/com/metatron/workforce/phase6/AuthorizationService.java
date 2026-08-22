package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Predicate;

/** Resolves authorization without conflating authorization with execution feasibility. */
public final class AuthorizationService {

    public AuthorizationDecision resolve(
            AuthorizationRequest request,
            Instant decisionTime,
            String decidedBy,
            Predicate<AuthorizationRequest> policyAllows) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decisionTime, "decisionTime");
        Objects.requireNonNull(policyAllows, "policyAllows");
        requireText(decidedBy, "decidedBy");

        if (!request.validAt(decisionTime)) {
            return decision(request, AuthorizationDecision.Outcome.DENY,
                    "authorization request is outside its validity window",
                    decisionTime, decidedBy);
        }

        boolean allowed = policyAllows.test(request);
        return decision(
                request,
                allowed ? AuthorizationDecision.Outcome.ALLOW : AuthorizationDecision.Outcome.DENY,
                allowed ? "applicable authorization policy permits the requested action"
                        : "applicable authorization policy denies the requested action",
                decisionTime,
                decidedBy);
    }

    public AuthorizationDecision review(
            AuthorizationRequest request,
            Instant decisionTime,
            String decidedBy,
            String reason) {
        return decision(request, AuthorizationDecision.Outcome.REVIEW, reason, decisionTime, decidedBy);
    }

    public AuthorizationDecision defer(
            AuthorizationRequest request,
            Instant decisionTime,
            String decidedBy,
            String reason) {
        return decision(request, AuthorizationDecision.Outcome.DEFER, reason, decisionTime, decidedBy);
    }

    private AuthorizationDecision decision(
            AuthorizationRequest request,
            AuthorizationDecision.Outcome outcome,
            String reason,
            Instant decisionTime,
            String decidedBy) {
        requireText(reason, "reason");
        return new AuthorizationDecision(
                "auth-" + request.requestId(),
                request.requestId(),
                outcome,
                reason,
                request.authorityReference(),
                request.delegationReference(),
                request.policyReference(),
                request.validFrom(),
                request.validUntil(),
                decisionTime,
                decidedBy,
                request.evidenceReference());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
