package com.metatron.workforce.phase9;

import java.util.Objects;

/**
 * Canonical cross-domain integration contract.
 *
 * Workforce does not absorb ownership of external domains.
 * It declares what it needs, what it emits, and the boundary
 * at which authority remains with the owning domain.
 */
public record IntegrationContract(
        String id,
        String sourceDomain,
        String targetDomain,
        String purpose,
        String inputContract,
        String outputContract,
        String authorityBoundary,
        String failureBehavior,
        String provenanceRequirement) {

    public IntegrationContract {
        Objects.requireNonNull(id);
        Objects.requireNonNull(sourceDomain);
        Objects.requireNonNull(targetDomain);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(inputContract);
        Objects.requireNonNull(outputContract);
        Objects.requireNonNull(authorityBoundary);
        Objects.requireNonNull(failureBehavior);
        Objects.requireNonNull(provenanceRequirement);
    }
}
