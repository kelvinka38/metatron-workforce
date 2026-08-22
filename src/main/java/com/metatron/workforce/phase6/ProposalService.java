package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;

/** Applies bounded proposal transitions and preserves decision evidence. */
public final class ProposalService {

    public Proposal submit(Proposal proposal) {
        requireState(proposal, Proposal.State.DRAFT);
        return copy(proposal, Proposal.State.SUBMITTED, null, null);
    }

    public Proposal startReview(Proposal proposal) {
        if (proposal.state() != Proposal.State.SUBMITTED && proposal.state() != Proposal.State.RETURNED) {
            throw new IllegalStateException("proposal must be submitted or returned before review");
        }
        return copy(proposal, Proposal.State.UNDER_REVIEW, null, null);
    }

    public Proposal approve(Proposal proposal, Instant at, String reason) {
        return decide(proposal, Proposal.State.APPROVED, at, reason);
    }

    public Proposal reject(Proposal proposal, Instant at, String reason) {
        return decide(proposal, Proposal.State.REJECTED, at, reason);
    }

    public Proposal returnForRevision(Proposal proposal, Instant at, String reason) {
        return decide(proposal, Proposal.State.RETURNED, at, reason);
    }

    public Proposal defer(Proposal proposal, Instant at, String reason) {
        return decide(proposal, Proposal.State.DEFERRED, at, reason);
    }

    public Proposal partiallyApprove(Proposal proposal, Instant at, String reason) {
        return decide(proposal, Proposal.State.PARTIALLY_APPROVED, at, reason);
    }

    private Proposal decide(Proposal proposal, Proposal.State target, Instant at, String reason) {
        Objects.requireNonNull(at, "at");
        requireText(reason, "reason");
        if (proposal.state() != Proposal.State.UNDER_REVIEW) {
            throw new IllegalStateException("proposal must be under review before a decision");
        }
        return copy(proposal, target, at, reason);
    }

    private static Proposal copy(Proposal proposal, Proposal.State state, Instant decidedAt, String reason) {
        return new Proposal(
                proposal.proposalId(), proposal.proposerId(), proposal.organizationContextId(),
                proposal.action(), proposal.scope(), state, proposal.createdAt(),
                decidedAt != null ? decidedAt : proposal.decidedAt(),
                reason != null ? reason : proposal.decisionReason(), proposal.evidenceReference());
    }

    private static void requireState(Proposal proposal, Proposal.State expected) {
        Objects.requireNonNull(proposal, "proposal");
        if (proposal.state() != expected) {
            throw new IllegalStateException("expected state " + expected + " but was " + proposal.state());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
