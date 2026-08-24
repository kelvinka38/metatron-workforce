package com.metatron.workforce.phase5;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase5RealityAcceptanceTest {

    private static final Instant EFFECTIVE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void resolvesWorkingDaysBreaksTimezoneAndOvernightShift() {
        var policy = new WorkingTimePolicy(
                "worker-1",
                ZoneId.of("Asia/Ho_Chi_Minh"),
                EFFECTIVE,
                null,
                Map.of(DayOfWeek.MONDAY, List.of(new WorkingTimePolicy.Shift(LocalTime.of(8, 0), LocalTime.of(16, 0))),
                       DayOfWeek.FRIDAY, List.of(new WorkingTimePolicy.Shift(LocalTime.of(22, 0), LocalTime.of(6, 0)))),
                Map.of(DayOfWeek.MONDAY, List.of(new WorkingTimePolicy.BreakWindow(LocalTime.of(12, 0), LocalTime.of(13, 0)))),
                List.of());

        assertEquals(AvailabilityStatus.AVAILABLE, policy.resolve(Instant.parse("2026-01-05T03:00:00Z")));
        assertEquals(AvailabilityStatus.UNAVAILABLE, policy.resolve(Instant.parse("2026-01-05T05:00:00Z")));
        assertEquals(AvailabilityStatus.UNAVAILABLE, policy.resolve(Instant.parse("2026-01-05T09:00:00Z")));
        assertEquals(AvailabilityStatus.AVAILABLE, policy.resolve(Instant.parse("2026-01-02T15:00:00Z")));
        assertEquals(AvailabilityStatus.AVAILABLE, policy.resolve(Instant.parse("2026-01-02T22:00:00Z")));
        assertEquals(AvailabilityStatus.UNAVAILABLE, policy.resolve(Instant.parse("2026-01-03T00:00:00Z")));
    }

    @Test
    void explicitUnavailabilityDoesNotRewriteRecurringWorkingRule() {
        var policy = new WorkingTimePolicy(
                "worker-2", ZoneId.of("Asia/Ho_Chi_Minh"), EFFECTIVE, null,
                Map.of(DayOfWeek.MONDAY, List.of(new WorkingTimePolicy.Shift(LocalTime.of(8, 0), LocalTime.of(16, 0)))),
                Map.of(),
                List.of(new WorkingTimePolicy.Unavailability(
                        Instant.parse("2026-01-05T04:00:00Z"),
                        Instant.parse("2026-01-05T06:00:00Z"), "leave")));

        assertEquals(AvailabilityStatus.UNAVAILABLE, policy.resolve(Instant.parse("2026-01-05T05:00:00Z")));
        assertEquals(AvailabilityStatus.AVAILABLE, policy.resolve(Instant.parse("2026-01-05T03:30:00Z")));
        assertEquals(AvailabilityStatus.UNKNOWN, policy.resolve(Instant.parse("2025-12-31T05:00:00Z")));
    }

    @Test
    void capacityPreservesFiniteRealityAndOvercommitment() {
        var capacity = new CapacitySnapshot(64, 0, 80, 192);
        assertEquals(64, capacity.available());
        assertEquals(128, capacity.deficit());
        assertEquals(0, capacity.remaining());
        assertTrue(capacity.overcommitted());
        assertEquals(0.5, capacity.utilization(32), 0.0001);
        assertEquals(1d / 3d, capacity.coverageRatio(), 0.0001);
    }

    @Test
    void staffingRequiresQualifiedWorkersRatherThanNominalHeadcount() {
        var staffing = new StaffingSnapshot(6, 10, 7, 2);
        assertEquals(0, staffing.deficit());
        assertEquals(4, staffing.qualifiedDeficit());
        assertEquals(2d / 6d, staffing.coverageRatio(), 0.0001);
    }

    @Test
    void resourceAuthorizationAndDependenciesRemainSeparateFromAvailability() {
        assertFalse(new ResourceConstraint("machine", 2, 2, false, true).executable());
        assertFalse(new ResourceConstraint("machine", 2, 2, true, false).executable());
        assertTrue(new ResourceConstraint("machine", 2, 2, true, true).executable());
    }

    @Test
    void economicEvidenceProducesOperationalEvidenceWithoutAccountingOwnership() {
        var evidence = new EconomicEvidence("work-1", 192, 64, 4, 2, 1000d);
        assertEquals(-128, evidence.laborVariance(), 0.0001);
        assertEquals(-2, evidence.resourceVariance(), 0.0001);
        assertEquals(100d, evidence.costPerWorkUnit(10));
    }

    @Test
    void constrainedScenarioCannotBecomeFullExecution() {
        var engine = new RealityConstraintEngine();
        var result = engine.evaluate(
                192,
                new CapacitySnapshot(64, 0, 0, 192),
                new StaffingSnapshot(6, 2, 2, 2),
                List.of(new ResourceConstraint("equipment", 4, 2, true, true)),
                true,
                true,
                true);

        assertEquals(ExecutionFeasibility.Status.PARTIAL, result.status());
        assertEquals(128, result.laborDeficit(), 0.0001);
        assertEquals(4, result.qualifiedStaffingDeficit());
    }

    @Test
    void hardRealityConstraintsBlockExecution() {
        var engine = new RealityConstraintEngine();
        var result = engine.evaluate(
                64,
                new CapacitySnapshot(64, 0, 0, 64),
                new StaffingSnapshot(1, 1, 1, 1),
                List.of(new ResourceConstraint("equipment", 1, 1, false, true)),
                false,
                false,
                false);

        assertEquals(ExecutionFeasibility.Status.BLOCKED, result.status());
        assertTrue(result.blockingReasons().contains("operating-window-closed"));
        assertTrue(result.blockingReasons().contains("budget-insufficient"));
        assertTrue(result.blockingReasons().contains("dependency-unsatisfied"));
        assertTrue(result.blockingReasons().contains("resource-unauthorized:equipment"));
    }

    @Test
    void historicalEvidenceRemainsValidAtItsOwnEffectiveTime() {
        var evidence = new RealityEvidence(
                "worker-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z"),
                "system",
                "team-a",
                "working-time-rule-1");

        assertTrue(evidence.validAt(Instant.parse("2026-01-15T00:00:00Z")));
        assertFalse(evidence.validAt(Instant.parse("2026-02-15T00:00:00Z")));
        assertEquals("working-time-rule-1", evidence.sourceRef());
    }
}
