package com.metatron.workforce.phase7;

import com.metatron.workforce.phase5.CapacitySnapshot;
import com.metatron.workforce.phase5.StaffingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase7ReportingTest {
    private static final Instant T0 = Instant.parse("2026-08-20T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-20T16:00:00Z");

    @Test
    void workerCanProduceTypedReportsWithEvidence() {
        var report = new WorkReport("r-1", "worker-1", "org-1", WorkReport.ReportType.DAILY,
                T0, T1, "completed 3 tasks; 1 blocked", "evidence-1", T1);
        assertEquals(WorkReport.ReportType.DAILY, report.type());
        assertEquals("worker-1", report.workerId());
        assertEquals("evidence-1", report.evidenceReference());
    }

    @Test
    void performanceExposesPlannedCommittedExecutedCompletedAndQuality() {
        var performance = new PerformanceSnapshot("team-1", 100, 80, 90, 75, 0.95, 0.9, 1200, 0.88, 0.8);
        assertEquals(0.75, performance.completionRatio(), 0.0001);
        assertEquals(0.90, performance.executionRatio(), 0.0001);
    }

    @Test
    void varianceIsActualMinusPlanned() {
        var variance = new Variance("labor", 100, 120);
        assertEquals(20, variance.value());
    }

    @Test
    void economicEvidenceFeedsEconomyWithoutClaimingAccountingTruth() {
        var evidence = new EconomicReportEvidence("ee-1", "org-1", "work-1", "worker-1",
                T0, T1, 1000, 1200, 80, 40, "labor-hours + resource usage", "evidence-1");
        assertEquals(200, evidence.costVariance());
        assertFalse(evidence.favorableCostVariance());
    }

    @Test
    void dashboardAnswersCoreManagementQuestions() {
        var service = new ReportingService();
        var performance = service.performance("org-1", 100, 90, 80, 70, 0.9, 0.8, 1200, 0.85, 0.75);
        var dashboard = service.dashboard(
                List.of("work-2"), List.of("work-1"), List.of("work-3"),
                new CapacitySnapshot(192, 128, 16, 192),
                new StaffingSnapshot(6, 4, 2, 2), performance,
                List.of("capacity deficit"), List.of("equipment shortage"),
                1200, 1000d,
                List.of(new EconomicReportEvidence("ee-1", "org-1", "work-1", "worker-1", T0, T1,
                        1000, 1200, 80, 40, "labor", "e-1")),
                Map.of("output", 70d));

        assertTrue(dashboard.blocked());
        assertTrue(dashboard.overBudget());
        assertEquals(48, dashboard.remainingCapacity(), 0.0001);
        assertEquals(2, dashboard.currentStaffing());
        assertEquals(70d, dashboard.organizationalMetrics().get("output"));
    }

    @Test
    void dashboardCollectionsAreImmutable() {
        var dashboard = new ManagementDashboard(List.of("w"), List.of(), List.of(), 1, 1,
                new PerformanceSnapshot("x", 1, 1, 1, 1, 1, 1, 1, 1, 1),
                List.of(), List.of(), 1, null, List.of(), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> dashboard.currentWorkIds().add("x2"));
    }
}
