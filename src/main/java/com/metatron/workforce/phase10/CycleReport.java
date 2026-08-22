package com.metatron.workforce.phase10;

import java.util.Objects;

public record CycleReport(
        String reportId,
        double plannedOutput,
        double actualOutput,
        double outputVariance,
        double plannedCost,
        double actualCost,
        double costVariance,
        int plannedLaborHours,
        int actualLaborHours,
        int laborVarianceHours,
        String performance,
        String issues,
        String provenance) {
    public CycleReport {
        Objects.requireNonNull(reportId);
        Objects.requireNonNull(performance);
        Objects.requireNonNull(issues);
        Objects.requireNonNull(provenance);
        if (provenance.isBlank()) {
            throw new IllegalArgumentException("report provenance is mandatory");
        }
    }
}
