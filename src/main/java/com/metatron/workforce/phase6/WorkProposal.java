package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.Objects;

public record WorkProposal(
        String proposalId,
        String actorId,
        String workId,
        String action,
        String scope,
        String contextId,
        Instant requestedAt) {
    public WorkProposal {
        require(proposalId, "proposalId"); require(actorId, "actorId"); require(workId, "workId");
        require(action, "action"); require(scope, "scope"); require(contextId, "contextId");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
