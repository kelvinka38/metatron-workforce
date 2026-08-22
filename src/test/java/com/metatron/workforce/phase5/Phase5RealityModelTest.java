package com.metatron.workforce.phase5;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase5RealityModelTest {

    @Test
    void workingTimeWindowIsFiniteAndTimeAware() {
        var window = new WorkingTimeWindow(
                Instant.parse("2026-01-01T08:00:00Z"),
                Instant.parse("2026-01-01T16:00:00Z"),
                480);

        assertTrue(window.contains(Instant.parse("2026-01-01T08:00:00Z")));
        assertTrue(window.contains(Instant.parse("2026-01-01T12:00:00Z")));
        assertFalse(window.contains(Instant.parse("2026-01-01T16:00:00Z")));
        assertThrows(IllegalArgumentException.class, () ->
                new WorkingTimeWindow(window.end(), window.start(), 1));
    }

    @Test
    void capacityPreservesAvailabilityDeficitAndCoverage() {
        var capacity = new CapacitySnapshot(192, 128, 32, 192);

        assertEquals(64, capacity.available());
        assertEquals(32, capacity.remaining());
        assertEquals(128, capacity.deficit());
        assertEquals(1d / 3d, capacity.coverageRatio(), 0.0001);
    }

    @Test
    void staffingDistinguishesCurrentAvailableAndQualifiedWorkers() {
        var staffing = new StaffingSnapshot(6, 10, 7, 4);

        assertEquals(0, staffing.deficit());
        assertEquals(2, staffing.qualifiedDeficit());
        assertEquals(4d / 6d, staffing.coverageRatio(), 0.0001);
    }

    @Test
    void resourceConstraintBlocksWhenQuantityAuthorizationOrDependencyFails() {
        var insufficient = new ResourceConstraint("machine-1", 4, 2, true, true);
        assertEquals(2, insufficient.deficit());
        assertFalse(insufficient.executable());

        var unauthorized = new ResourceConstraint("machine-2", 1, 1, false, true);
        assertFalse(unauthorized.executable());

        var blockedDependency = new ResourceConstraint("machine-3", 1, 1, true, false);
        assertFalse(blockedDependency.executable());

        var executable = new ResourceConstraint("machine-4", 1, 1, true, true);
        assertTrue(executable.executable());
    }

    @Test
    void economicEvidencePreservesOperationalVarianceAndOptionalCostBasis() {
        var evidence = new EconomicEvidence("work-1", 100, 120, 500, 620, 1000d);

        assertEquals(20, evidence.laborVariance());
        assertEquals(120, evidence.resourceVariance());
        assertEquals(100d, evidence.costPerWorkUnit(10));
        assertNull(new EconomicEvidence("work-2", 1, 1, 1, 1, null).costPerWorkUnit(1));
        assertNull(evidence.costPerWorkUnit(0));
    }

    @Test
    void simulationScenarioKeepsAssumptionsExplicitAndImmutable() {
        var assumptions = Map.of("workers", 24d, "productivity", 1d);
        var scenario = new SimulationScenario("scenario-1", "baseline-1", assumptions, Map.of("coverage", 1d));

        assertTrue(scenario.hasAssumption("workers"));
        assertEquals(24d, scenario.assumptions().get("workers"));
        assertThrows(UnsupportedOperationException.class, () -> scenario.assumptions().put("budget", 1d));
    }

    @Test
    void phase5ModelsRejectImpossibleInputs() {
        assertThrows(IllegalArgumentException.class, () ->
                new CapacitySnapshot(10, 11, 0, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new StaffingSnapshot(1, 2, 3, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new StaffingSnapshot(1, 1, 1, 2));
        assertThrows(IllegalArgumentException.class, () ->
                new ResourceConstraint("resource", 2, -1, true, true));
        assertThrows(IllegalArgumentException.class, () ->
                new EconomicEvidence("work", -1, 1, 1, 1, 1d));
    }
}

