package com.metatron.workforce.phase9;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase9IntegrationAcceptanceTest {

    @Test
    void canonicalCrossDomainContractsExist() {
        List<IntegrationContract> contracts =
                Phase9IntegrationRegistry.canonicalContracts();

        assertEquals(6, contracts.size());

        assertTrue(contracts.stream().anyMatch(
                c -> c.id().equals("INT-WORKFORCE-AUTHORIZATION")));

        assertTrue(contracts.stream().anyMatch(
                c -> c.id().equals("INT-WORKFORCE-GATEWAY")));

        assertTrue(contracts.stream().anyMatch(
                c -> c.id().equals("INT-WORKFORCE-EXECUTION")));

        assertTrue(contracts.stream().anyMatch(
                c -> c.id().equals("INT-WORKFORCE-OBSERVATION")));

        assertTrue(contracts.stream().anyMatch(
                c -> c.id().equals("INT-WORKFORCE-KNOWLEDGE")));

        assertTrue(contracts.stream().anyMatch(
                c -> c.id().equals("INT-WORKFORCE-ECONOMY")));
    }

    @Test
    void everyContractHasRequiredIntegrationBoundary() {
        for (IntegrationContract c :
                Phase9IntegrationRegistry.canonicalContracts()) {

            assertNotNull(c.sourceDomain());
            assertNotNull(c.targetDomain());
            assertNotNull(c.inputContract());
            assertNotNull(c.outputContract());
            assertNotNull(c.authorityBoundary());
            assertNotNull(c.failureBehavior());
            assertNotNull(c.provenanceRequirement());

            assertFalse(c.authorityBoundary().isBlank());
            assertFalse(c.failureBehavior().isBlank());
            assertFalse(c.provenanceRequirement().isBlank());
        }
    }

    @Test
    void workforceDoesNotAbsorbExternalDomainOwnership() {
        for (IntegrationContract c :
                Phase9IntegrationRegistry.canonicalContracts()) {

            assertNotEquals("WORKFORCE", c.targetDomain(),
                    "External domain ownership must remain explicit.");
        }
    }

    @Test
    void failuresRemainFailuresAndCannotManufactureAuthority() {
        IntegrationContract authorization =
                Phase9IntegrationRegistry.canonicalContracts()
                        .stream()
                        .filter(c -> c.id()
                                .equals("INT-WORKFORCE-AUTHORIZATION"))
                        .findFirst()
                        .orElseThrow();

        assertTrue(
                authorization.failureBehavior()
                        .toLowerCase()
                        .contains("deny"));
    }

    @Test
    void provenanceIsMandatoryAcrossEveryBoundary() {
        for (IntegrationContract c :
                Phase9IntegrationRegistry.canonicalContracts()) {

            assertTrue(
                    c.provenanceRequirement().toLowerCase()
                            .contains("preserve"));
        }
    }
}
