package com.metatron.workforce.phase5;

import java.util.Objects;

public record EconomicEvidence(
        String workId,
        double plannedLaborHours,
        double actualLaborHours,
        double plannedResourceUnits,
        double actualResourceUnits,
        Double costBasis) {

    public EconomicEvidence {
        requireText("workId", workId);
        requireNonNegative("plannedLaborHours", plannedLaborHours);
        requireNonNegative("actualLaborHours", actualLaborHours);
        requireNonNegative("plannedResourceUnits", plannedResourceUnits);
        requireNonNegative("actualResourceUnits", actualResourceUnits);
        if (costBasis != null && (!Double.isFinite(costBasis) || costBasis < 0d)) {
            throw new IllegalArgumentException("costBasis must be null or non-negative");
        }
    }

    public double laborVariance() {
        return actualLaborHours - plannedLaborHours;
    }

    public double resourceVariance() {
        return actualResourceUnits - plannedResourceUnits;
    }

    public Double costPerWorkUnit(double completedWorkUnits) {
        if (costBasis == null) {
            return null;
        }
        if (!Double.isFinite(completedWorkUnits) || completedWorkUnits <= 0d) {
            return null;
        }
        return costBasis / completedWorkUnits;
    }

    private static void requireText(String name, String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
