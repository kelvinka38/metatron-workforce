package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record GovernanceDenial(String denialId, String code, String objectiveId, String stepId,
                               String actionRef, String reason, List<String> evidenceReferences,
                               Instant observedAt) {
    public GovernanceDenial {
        denialId = require(denialId, "denialId"); code = require(code, "code"); objectiveId = require(objectiveId, "objectiveId");
        stepId = stepId == null ? "" : stepId.trim(); actionRef = actionRef == null ? "" : actionRef.trim();
        reason = require(reason, "reason"); evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field); String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank"); return normalized;
    }
}
