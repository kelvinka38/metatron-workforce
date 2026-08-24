package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Predicate;

public final class AuthorizationService {
    private final Phase6AuthorizationPolicy policy;

    /** Compatibility constructor for the historical authorization test surface. */
    @Deprecated
    public AuthorizationService() {
        this(req -> Phase6AuthorizationPolicy.Decision.allowed("AUTH-COMPAT"));
    }

    public AuthorizationService(Phase6AuthorizationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public AuthorizationResult authorize(WorkProposal proposal, ApprovalDecision approval, AuthorizationRequest request) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(approval, "approval");
        Objects.requireNonNull(request, "request");
        if (!proposal.proposalId().equals(approval.proposalId())) return AuthorizationResult.denied("proposal-mismatch", "approval does not belong to proposal");
        if (!proposal.actorId().equals(request.actorId())) return AuthorizationResult.denied("actor-mismatch", "authorization actor differs from proposal actor");
        if (!proposal.action().equals(request.action())) return AuthorizationResult.denied("action-mismatch", "authorization action differs from proposal");
        if (!proposal.scope().equals(request.scope())) return AuthorizationResult.denied("scope-mismatch", "authorization scope differs from proposal");
        if (!proposal.contextId().equals(request.contextId())) return AuthorizationResult.denied("context-mismatch", "authorization context differs from proposal");
        if (!approval.approved()) return AuthorizationResult.denied(approval.authorityReference(), "proposal not approved");
        if (request.at().isBefore(approval.approvedAt())) return AuthorizationResult.denied("approval-not-effective", "approval not yet effective");
        if (request.at().isBefore(proposal.requestedAt())) return AuthorizationResult.denied("time-invalid", "authorization predates proposal");
        var decision = policy.authorize(request);
        return decision.allowed()
                ? AuthorizationResult.allowed(decision.authorizationReference())
                : AuthorizationResult.denied(decision.authorizationReference(), decision.reason());
    }

    /**
     * Historical resolve surface retained as a compatibility adapter. It never
     * bypasses the supplied policy predicate, actor/context data, or validity window.
     */
    @Deprecated
    public AuthorizationDecision resolve(
            AuthorizationRequest request,
            Instant evaluatedAt,
            String decidedBy,
            Predicate<AuthorizationRequest> externalPolicy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(externalPolicy, "externalPolicy");

        boolean withinWindow = !evaluatedAt.isBefore(request.at());
        boolean policyAllowed = withinWindow && externalPolicy.test(request);
        AuthorizationDecision.Outcome outcome = policyAllowed ? AuthorizationDecision.Outcome.ALLOW : AuthorizationDecision.Outcome.DENY;
        String reason;
        if (!withinWindow) reason = "authorization not yet effective";
        else if (request.expiresAtIfLegacy().isPresent() && !evaluatedAt.isBefore(request.expiresAtIfLegacy().get())) reason = "authorization expired";
        else if (!policyAllowed) reason = "external policy denied";
        else reason = "authorized";

        Instant effectiveAt = request.effectiveAtIfLegacy().orElse(request.at());
        Instant expiresAt = request.expiresAtIfLegacy().orElse(null);
        return new AuthorizationDecision(
                request.authorityId(),
                request.requestId(),
                outcome,
                reason,
                request.authorityId(),
                request.delegationIdIfLegacy().orElse("DELEGATION-NONE"),
                request.policyIdIfLegacy().orElse("POLICY-COMPAT"),
                effectiveAt,
                expiresAt,
                evaluatedAt,
                decidedBy,
                request.resourceId());
    }

    public record AuthorizationResult(boolean allowed, String authorizationReference, String reason) {
        public AuthorizationResult {
            if (authorizationReference == null || authorizationReference.isBlank()) throw new IllegalArgumentException("authorizationReference must not be blank");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }
        public static AuthorizationResult allowed(String reference) { return new AuthorizationResult(true, reference, "authorized"); }
        public static AuthorizationResult denied(String reference, String reason) { return new AuthorizationResult(false, reference, reason); }
    }
}
