package com.metatron.workforce.phase10;

import java.util.Objects;

/** Operational economic evidence only; Economy remains authoritative for accounting truth. */
public record EconomicSliceEvidence(
        double plannedRevenueEvidence,
        double actualRevenueEvidence,
        double plannedCostEvidence,
        double actualCostEvidence,
        double plannedContributionEvidence,
        double actualContributionEvidence,
        double contributionVariance,
        String allocationBasis,
        String authorityBoundary,
        String provenance) {
    public EconomicSliceEvidence {
        Objects.requireNonNull(allocationBasis);
        Objects.requireNonNull(authorityBoundary);
        Objects.requireNonNull(provenance);
        if (provenance.isBlank() || authorityBoundary.isBlank()) {
            throw new IllegalArgumentException("economic authority and provenance are mandatory");
        }
    }
}
