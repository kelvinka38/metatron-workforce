package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Institutional completion verdict. Only ALLOW permits a terminal completion transition. */
public record CompletionDecision(String decisionId, Verdict verdict, String objectiveId, String stepId,
                                 String planId, int planVersion, String authorityDigest,
                                 List<String> codes, List<String> evidenceReferences, Instant decidedAt) {
    public enum Verdict { ALLOW, DENY, CHANGE_REQUIRED }

    public CompletionDecision {
        decisionId = require(decisionId, "decisionId"); Objects.requireNonNull(verdict, "verdict");
        objectiveId = require(objectiveId, "objectiveId"); stepId = stepId == null ? "" : stepId.trim();
        planId = require(planId, "planId"); if (planVersion < 1) throw new IllegalArgumentException("planVersion must be positive");
        authorityDigest = require(authorityDigest, "authorityDigest");
        codes = codes == null ? List.of() : List.copyOf(codes);
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public boolean allowed() { return verdict == Verdict.ALLOW; }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field); String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank"); return normalized;
    }
}
