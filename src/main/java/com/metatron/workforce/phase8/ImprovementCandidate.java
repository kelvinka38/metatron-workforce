package com.metatron.workforce.phase8;

import java.time.Instant;

public record ImprovementCandidate(
        String candidateId,
        String evaluationId,
        String proposedChange,
        Status status,
        Instant proposedAt) {

    public enum Status { PROPOSED, VALIDATED, ADOPTED, REJECTED }

    public ImprovementCandidate {
        requireText(candidateId, "candidateId");
        requireText(evaluationId, "evaluationId");
        requireText(proposedChange, "proposedChange");
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (proposedAt == null) throw new IllegalArgumentException("proposedAt must not be null");

        if (status == Status.ADOPTED)
            throw new IllegalArgumentException("candidate cannot become ADOPTED without explicit validation/adoption decision");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }
}
