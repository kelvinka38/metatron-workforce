package com.metatron.workforce.phase10;

import java.util.Objects;

public record ExecutionOutcome(
        String executionId,
        boolean success,
        int actualLaborHours,
        double actualCost,
        double actualOutput,
        String report,
        String failure,
        String provenance) {
    public ExecutionOutcome {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(report);
        Objects.requireNonNull(failure);
        Objects.requireNonNull(provenance);
        if (actualLaborHours < 0 || actualCost < 0 || actualOutput < 0) {
            throw new IllegalArgumentException("execution values cannot be negative");
        }
        if (provenance.isBlank()) {
            throw new IllegalArgumentException("execution provenance is mandatory");
        }
        if (success && !failure.isBlank()) {
            throw new IllegalArgumentException("successful execution cannot carry a failure");
        }
        if (!success && failure.isBlank()) {
            throw new IllegalArgumentException("failed execution must retain its failure");
        }
    }
}
