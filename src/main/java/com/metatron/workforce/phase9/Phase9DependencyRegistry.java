package com.metatron.workforce.phase9;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * G9 completeness registry for every external dependency named by Phase 9.
 *
 * The six executable D1/D2 boundaries remain canonical integration contracts.
 * Governance and Data are represented here because G9 covers the complete
 * Phase 9 dependency surface, not only the executable boundary adapters.
 */
public final class Phase9DependencyRegistry {

    private Phase9DependencyRegistry() {
    }

    public static List<ExternalDependency> canonicalDependencies() {
        List<ExternalDependency> dependencies = Phase9IntegrationRegistry.canonicalContracts().stream()
                .map(Phase9DependencyRegistry::fromContract)
                .collect(Collectors.toList());

        dependencies.add(new ExternalDependency(
                "DEP-WORKFORCE-GOVERNANCE",
                "GOVERNANCE",
                "institutional-policy-authority",
                "policy/rule request",
                "policy decision + authority reference",
                "Governance owns institutional authority; Workforce cannot manufacture policy",
                "unavailable or denied governance remains non-authoritative",
                "preserve policy identity, authority reference, decision time, and provenance"));

        dependencies.add(new ExternalDependency(
                "DEP-WORKFORCE-DATA",
                "DATA",
                "institutional-records-and-operational-data",
                "institutional record / operational data request",
                "record/data result + provenance reference",
                "Data ownership and authoritative record semantics remain external to Workforce",
                "missing, invalid, or unavailable data remains unavailable",
                "preserve record identity, source, timestamp, lineage, and provenance"));

        return List.copyOf(dependencies);
    }

    private static ExternalDependency fromContract(IntegrationContract contract) {
        Objects.requireNonNull(contract);
        return new ExternalDependency(
                "DEP-" + contract.id().substring("INT-".length()),
                contract.targetDomain(),
                contract.targetDomain().toLowerCase() + "-boundary",
                contract.inputContract(),
                contract.outputContract(),
                contract.authorityBoundary(),
                contract.failureBehavior(),
                contract.provenanceRequirement());
    }
}
