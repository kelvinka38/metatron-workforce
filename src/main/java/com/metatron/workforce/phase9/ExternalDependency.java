package com.metatron.workforce.phase9;

import java.util.Objects;

/**
 * Canonical Phase 9 dependency coverage record.
 *
 * This record describes an external dependency at the G9 level. It does not
 * grant Workforce ownership of the dependency or its authority.
 */
public record ExternalDependency(
        String id,
        String ownerDomain,
        String interfaceName,
        String inputContract,
        String outputContract,
        String authorityBoundary,
        String failureBehavior,
        String provenanceRequirement) {

    public ExternalDependency {
        Objects.requireNonNull(id);
        Objects.requireNonNull(ownerDomain);
        Objects.requireNonNull(interfaceName);
        Objects.requireNonNull(inputContract);
        Objects.requireNonNull(outputContract);
        Objects.requireNonNull(authorityBoundary);
        Objects.requireNonNull(failureBehavior);
        Objects.requireNonNull(provenanceRequirement);

        if (id.isBlank() || ownerDomain.isBlank() || interfaceName.isBlank()
                || inputContract.isBlank() || outputContract.isBlank()
                || authorityBoundary.isBlank() || failureBehavior.isBlank()
                || provenanceRequirement.isBlank()) {
            throw new IllegalArgumentException("Phase 9 dependency fields must not be blank");
        }
    }
}
