package com.metatron.workforce.observation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Cross-domain Observation result consumed by Workforce. Observation owns the observed-state claim;
 * Workforce only references it when deciding whether its acceptance criteria are satisfied.
 */
public record ObservationReport(
        String reportId,
        String requirementId,
        String objectiveId,
        String target,
        String observedState,
        String method,
        Instant observedAt,
        Instant effectiveAt,
        List<String> evidenceReferences,
        double confidence,
        Quality quality,
        String variance,
        CriterionResult criterionResult) {

    public enum Quality { HIGH, MEDIUM, LOW, INSUFFICIENT }
    public enum CriterionResult { PASS, FAIL, INCONCLUSIVE }

    public ObservationReport {
        require(reportId, "reportId");
        require(requirementId, "requirementId");
        require(objectiveId, "objectiveId");
        target = target == null ? "" : target.trim();
        require(observedState, "observedState");
        require(method, "method");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        evidenceReferences = normalize(evidenceReferences);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        Objects.requireNonNull(quality, "quality");
        variance = variance == null ? "" : variance.trim();
        Objects.requireNonNull(criterionResult, "criterionResult");
        if (criterionResult == CriterionResult.PASS && evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException("PASS Observation requires evidence");
        }
        if (criterionResult == CriterionResult.PASS && quality == Quality.INSUFFICIENT) {
            throw new IllegalArgumentException("INSUFFICIENT Observation cannot PASS");
        }
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
