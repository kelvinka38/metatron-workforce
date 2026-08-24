package com.metatron.workforce.phase6;

import java.util.Objects;

public final class AuthorizationService {
    private final Phase6AuthorizationPolicy policy;

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
        if (request.at().isBefore(proposal.requestedAt())) return AuthorizationResult.denied("time-invalid", "authorization predates proposal");
        var decision = policy.authorize(request);
        return decision.allowed()
                ? AuthorizationResult.allowed(decision.authorizationReference())
                : AuthorizationResult.denied(decision.authorizationReference(), decision.reason());
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
