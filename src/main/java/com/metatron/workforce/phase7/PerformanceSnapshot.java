package com.metatron.workforce.phase7;

public record PerformanceSnapshot(
        String subjectId,
        double planned,
        double committed,
        double executed,
        double completed,
        double quality,
        double timeliness,
        double cost,
        double resourceEfficiency,
        double outcome) {

    public PerformanceSnapshot {
        requireText(subjectId, "subjectId");
        requireFiniteNonNegative(planned, "planned");
        requireFiniteNonNegative(committed, "committed");
        requireFiniteNonNegative(executed, "executed");
        requireFiniteNonNegative(completed, "completed");
        requireFiniteNonNegative(quality, "quality");
        requireFiniteNonNegative(timeliness, "timeliness");
        requireFiniteNonNegative(cost, "cost");
        requireFiniteNonNegative(resourceEfficiency, "resourceEfficiency");
        requireFiniteNonNegative(outcome, "outcome");
        if (completed > executed) throw new IllegalArgumentException("completed cannot exceed executed");
    }

    public double completionRatio() { return planned == 0d ? 1d : completed / planned; }
    public double executionRatio() { return planned == 0d ? 1d : executed / planned; }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0d) throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
}
