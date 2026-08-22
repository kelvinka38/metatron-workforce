package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;

/** Material proposal lifecycle used before authorization and execution. */
public record Proposal(
        String proposalId,
        String proposerId,
        String organizationContextId,
        String action,
        String scope,
        State state,
        Instant createdAt,
        Instant decidedAt,
        String decisionReason,
        String evidenceReference) {

    public enum State { DRAFT, SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, RETURNED, DEFERRED, PARTIALLY_APPROVED }

    public Proposal {
        requireText(proposalId, "proposalId");
        requireText(proposerId, "proposerId");
        requireText(organizationContextId, "organizationContextId");
        requireText(action, "action");
        requireText(scope, "scope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        requireText(evidenceReference, "evidenceReference");
        if (decidedAt != null && decidedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("decidedAt must not precede createdAt");
        }
    }

    public boolean decided() {
        return switch (state) {
            case APPROVED, REJECTED, RETURNED, DEFERRED, PARTIALLY_APPROVED -> true;
            default -> false;
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
