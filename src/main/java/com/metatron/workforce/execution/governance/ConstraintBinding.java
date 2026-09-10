package com.metatron.workforce.execution.governance;

import java.util.Objects;

/** Machine/review representation of one normative constraint, always tied back to authority. */
public record ConstraintBinding(
        String constraintId,
        String authorityArtifactIdentity,
        Kind kind,
        String predicateType,
        String predicatePayload,
        String reviewRequirement) {

    public enum Kind { MACHINE, REVIEW }

    public ConstraintBinding {
        constraintId = require(constraintId, "constraintId");
        authorityArtifactIdentity = require(authorityArtifactIdentity, "authorityArtifactIdentity");
        Objects.requireNonNull(kind, "kind");
        predicateType = predicateType == null ? "" : predicateType.trim();
        predicatePayload = predicatePayload == null ? "" : predicatePayload.trim();
        reviewRequirement = reviewRequirement == null ? "" : reviewRequirement.trim();
        if (kind == Kind.MACHINE && predicateType.isBlank()) {
            throw new IllegalArgumentException("MACHINE constraint requires predicateType");
        }
        if (kind == Kind.REVIEW && reviewRequirement.isBlank()) {
            throw new IllegalArgumentException("REVIEW constraint requires reviewRequirement");
        }
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
