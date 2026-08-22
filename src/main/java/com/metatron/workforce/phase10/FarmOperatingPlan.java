package com.metatron.workforce.phase10;

import java.util.Objects;

public record FarmOperatingPlan(
        String planId,
        String farmId,
        int requiredLaborHours,
        int availableLaborHours,
        int capacityDeficitHours,
        int workerCount,
        int shiftHoursPerWorker,
        double plannedLaborCost,
        double plannedResourceCost,
        double expectedOutput,
        String schedule,
        String risks,
        String provenance) {
    public FarmOperatingPlan {
        Objects.requireNonNull(planId);
        Objects.requireNonNull(farmId);
        Objects.requireNonNull(schedule);
        Objects.requireNonNull(risks);
        Objects.requireNonNull(provenance);
        if (requiredLaborHours < 0 || availableLaborHours < 0 || capacityDeficitHours < 0
                || workerCount < 0 || shiftHoursPerWorker < 0
                || plannedLaborCost < 0 || plannedResourceCost < 0 || expectedOutput < 0) {
            throw new IllegalArgumentException("plan values cannot be negative");
        }
    }
}
