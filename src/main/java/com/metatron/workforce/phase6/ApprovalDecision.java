package com.metatron.workforce.phase6;

import java.time.Instant;

public record ApprovalDecision(
        String decisionId,
        String proposalId,
        String approverId,
        boolean approved,
        Instant decidedAt,
        String authorityReference,
        String reason) {
    public ApprovalDecision {
        require(decisionId, "decisionId"); require(proposalId, "proposalId"); require(approverId, "approverId");
        require(authorityReference, "authorityReference"); require(reason, "reason");
        if (decidedAt == null) throw new IllegalArgumentException("decidedAt must not be null");
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
