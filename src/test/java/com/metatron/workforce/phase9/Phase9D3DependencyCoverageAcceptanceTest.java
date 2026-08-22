package com.metatron.workforce.phase9;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class Phase9D3DependencyCoverageAcceptanceTest {

    @Test
    void g9CoversTheCompletePhase9DependencySurface() {
        List<ExternalDependency> dependencies = Phase9DependencyRegistry.canonicalDependencies();

        assertEquals(8, dependencies.size());

        assertEquals(Set.of(
                "GOVERNANCE",
                "AUTHORIZATION",
                "GATEWAY",
                "EXECUTION",
                "OBSERVATION",
                "KNOWLEDGE",
                "ECONOMY",
                "DATA"),
                dependencies.stream()
                        .map(ExternalDependency::ownerDomain)
                        .collect(Collectors.toSet()));
    }

    @Test
    void everyDependencyHasCompleteBoundarySemantics() {
        for (ExternalDependency dependency : Phase9DependencyRegistry.canonicalDependencies()) {
            assertFalse(dependency.id().isBlank());
            assertFalse(dependency.ownerDomain().isBlank());
            assertFalse(dependency.interfaceName().isBlank());
            assertFalse(dependency.inputContract().isBlank());
            assertFalse(dependency.outputContract().isBlank());
            assertFalse(dependency.authorityBoundary().isBlank());
            assertFalse(dependency.failureBehavior().isBlank());
            assertFalse(dependency.provenanceRequirement().isBlank());
            assertTrue(dependency.provenanceRequirement().toLowerCase().contains("preserve"));
        }
    }

    @Test
    void executableD2BoundariesRemainTraceableToG9Dependencies() {
        Set<String> dependencyIds = Phase9DependencyRegistry.canonicalDependencies().stream()
                .map(ExternalDependency::id)
                .collect(Collectors.toSet());

        for (IntegrationContract contract : Phase9IntegrationRegistry.canonicalContracts()) {
            String dependencyId = "DEP-" + contract.id().substring("INT-".length());
            assertTrue(dependencyIds.contains(dependencyId));
        }
    }

    @Test
    void governanceAndDataCoverageDoNotGrantWorkforceExternalAuthority() {
        ExternalDependency governance = dependency("DEP-WORKFORCE-GOVERNANCE");
        ExternalDependency data = dependency("DEP-WORKFORCE-DATA");

        assertTrue(governance.authorityBoundary().toLowerCase().contains("external"));
        assertTrue(data.authorityBoundary().toLowerCase().contains("external"));
        assertTrue(governance.failureBehavior().toLowerCase().contains("non-authoritative"));
        assertTrue(data.failureBehavior().toLowerCase().contains("unavailable"));
    }

    @Test
    void noDependencyMayUseWorkforceAsItsAuthorityOwner() {
        for (ExternalDependency dependency : Phase9DependencyRegistry.canonicalDependencies()) {
            assertNotEquals("WORKFORCE", dependency.ownerDomain());
        }
    }

    private static ExternalDependency dependency(String id) {
        return Phase9DependencyRegistry.canonicalDependencies().stream()
                .filter(d -> d.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
