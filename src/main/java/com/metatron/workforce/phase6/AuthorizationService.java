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

        // Canonical Phase-6 validation order: proposal identity -> request alignment
        // -> proposal chronology -> approval state/effectiveness -> external policy.
        // Keep each boundary explicit so a later condition can never mask an earlier one.
        if (!proposal.proposalId().equals(approval.proposalId())) {
            return AuthorizationResult.denied("proposal-mismatch", "approval does not belong to proposal");
        }

        String alignmentFailure = alignmentFailure(proposal, request);
        if (alignmentFailure != null) {
            return switch (alignmentFailure) {
                case "actor-mismatch" -> AuthorizationResult.denied("actor-mismatch", "authorization actor differs from proposal actor");
                case "action-mismatch" -> AuthorizationResult.denied("action-mismatch", "authorization action differs from proposal");
                case "scope-mismatch" -> AuthorizationResult.denied("scope-mismatch", "authorization scope differs from proposal");
                case "context-mismatch" -> AuthorizationResult.denied("context-mismatch", "authorization context differs from proposal");
                default -> throw new IllegalStateException("unknown alignment failure: " + alignmentFailure);
            };
        }

        if (request.at().isBefore(proposal.requestedAt())) {
            return AuthorizationResult.denied("time-invalid", "authorization predates proposal");
        }
        if (!approval.approved()) {
            return AuthorizationResult.denied(approval.authorityReference(), "proposal not approved");
        }
        if (request.at().isBefore(approval.decidedAt())) {
            return AuthorizationResult.denied("approval-not-effective", "approval not yet effective");
        }

        var decision = policy.authorize(request);
        return decision.allowed()
                ? AuthorizationResult.allowed(decision.authorizationReference())
                : AuthorizationResult.denied(decision.authorizationReference(), decision.reason());
    }

    private static String alignmentFailure(WorkProposal proposal, AuthorizationRequest request) {
        if (!Objects.equals(proposal.actorId(), request.actorId())) return "actor-mismatch";
        if (!Objects.equals(proposal.action(), request.action())) return "action-mismatch";
        if (!Objects.equals(proposal.scope(), request.scope())) return "scope-mismatch";
        if (!Objects.equals(proposal.contextId(), request.contextId())) return "context-mismatch";
        return null;
    }

    /**
     * Historical resolve surface retained as a compatibility adapter. It never
     * bypasses the supplied policy predicate or authorization validity window.
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

        boolean beforeEffective = evaluatedAt.isBefore(request.effectiveAtIfLegacy().orElse(request.at()));
        boolean expired = request.expiresAtIfLegacy().isPresent() && !evaluatedAt.isBefore(request.expiresAtIfLegacy().get());
        boolean policyAllowed = !beforeEffective && !expired && externalPolicy.test(request);
        AuthorizationDecision.Outcome outcome = policyAllowed ? AuthorizationDecision.Outcome.ALLOW : AuthorizationDecision.Outcome.DENY;
        String reason;
        if (beforeEffective) reason = "authorization not yet effective";
        else if (expired) reason = "authorization expired";
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