package com.metatron.workforce.phase7;

import java.time.Instant;
import java.util.Objects;

/** Workforce evidence for Economy; not authoritative accounting truth. */
public record EconomicReportEvidence(
        String evidenceId,
        String organizationId,
        String workId,
        String workerId,
        Instant periodStart,
        Instant periodEnd,
        double plannedCost,
        double actualCost,
        double laborHours,
        double resourceUnits,
        String allocationBasis,
        String evidenceReference) {

    public EconomicReportEvidence {
        requireText(evidenceId, "evidenceId");
        requireText(organizationId, "organizationId");
        requireText(workId, "workId");
        requireText(workerId, "workerId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (!periodEnd.isAfter(periodStart)) throw new IllegalArgumentException("periodEnd must be after periodStart");
        requireNonNegative(plannedCost, "plannedCost");
        requireNonNegative(actualCost, "actualCost");
        requireNonNegative(laborHours, "laborHours");
        requireNonNegative(resourceUnits, "resourceUnits");
        requireText(allocationBasis, "allocationBasis");
        requireText(evidenceReference, "evidenceReference");
    }

    public double costVariance() { return actualCost - plannedCost; }
    public boolean favorableCostVariance() { return costVariance() <= 0d; }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
    private static void requireNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0d) throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
}
