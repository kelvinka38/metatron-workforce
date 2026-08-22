package com.metatron.workforce.phase7;

import com.metatron.workforce.phase5.CapacitySnapshot;
import com.metatron.workforce.phase5.StaffingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase7GateAcceptanceTest {

    private static final Instant START =
            Instant.parse("2026-08-20T08:00:00Z");

    private static final Instant END =
            Instant.parse("2026-08-20T16:00:00Z");

    @Test
    void supportsAllRequiredReportTypes() {
        assertEquals(
                7,
                WorkReport.ReportType.values().length);

        assertNotNull(WorkReport.ReportType.DAILY);
        assertNotNull(WorkReport.ReportType.PERIODIC);
        assertNotNull(WorkReport.ReportType.TASK);
        assertNotNull(WorkReport.ReportType.EXCEPTION);
        assertNotNull(WorkReport.ReportType.INCIDENT);
        assertNotNull(WorkReport.ReportType.PERFORMANCE);
        assertNotNull(WorkReport.ReportType.ECONOMIC);
    }

    @Test
    void reportIsAttributableAndTimeBound() {
        var report = new WorkReport(
                "report-001",
                "worker-001",
                "org-001",
                WorkReport.ReportType.PERFORMANCE,
                START,
                END,
                "performance observation",
                "evidence-001",
                END);

        assertEquals("worker-001", report.workerId());
        assertEquals("org-001", report.organizationContextId());
        assertEquals("evidence-001", report.evidenceReference());
        assertEquals(START, report.periodStart());
        assertEquals(END, report.periodEnd());
        assertEquals(END, report.reportedAt());
    }

    @Test
    void reportRejectsInvalidHistoricalWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkReport(
                        "report-002",
                        "worker-001",
                        "org-001",
                        WorkReport.ReportType.DAILY,
                        END,
                        START,
                        "invalid",
                        "evidence-002",
                        END));
    }

    @Test
    void performancePreservesPlannedAndActualDimensions() {
        var performance = new PerformanceSnapshot(
                "team-001",
                100,
                90,
                80,
                70,
                0.95,
                0.90,
                1200,
                0.85,
                0.75);

        assertEquals(100, performance.planned());
        assertEquals(90, performance.committed());
        assertEquals(80, performance.executed());
        assertEquals(70, performance.completed());

        assertEquals(0.70, performance.completionRatio(), 0.0001);
        assertEquals(0.80, performance.executionRatio(), 0.0001);
    }

    @Test
    void zeroPlannedQuantityDoesNotProduceInvalidRatio() {
        var performance = new PerformanceSnapshot(
                "team-002",
                0,
                0,
                0,
                0,
                1,
                1,
                0,
                1,
                1);

        assertEquals(1.0, performance.completionRatio());
        assertEquals(1.0, performance.executionRatio());
    }

    @Test
    void completedQuantityCannotExceedExecutedQuantity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PerformanceSnapshot(
                        "team-003",
                        100,
                        100,
                        50,
                        60,
                        1,
                        1,
                        0,
                        1,
                        1));
    }

    @Test
    void varianceUsesActualMinusPlanned() {
        var positive = new Variance("labor", 100, 120);
        var negative = new Variance("output", 120, 100);

        assertEquals(20, positive.value());
        assertEquals(-20, negative.value());
    }

    @Test
    void economicEvidencePreservesOperationalBoundary() {
        var evidence = new EconomicReportEvidence(
                "econ-001",
                "org-001",
                "work-001",
                "worker-001",
                START,
                END,
                1000,
                1200,
                120,
                40,
                "labor-hours + resource usage",
                "evidence-001");

        assertEquals(1000, evidence.plannedCost());
        assertEquals(1200, evidence.actualCost());
        assertEquals(200, evidence.costVariance());
        assertFalse(evidence.favorableCostVariance());
        assertEquals("evidence-001", evidence.evidenceReference());
    }

    @Test
    void managementDashboardPreservesBlockedWorkAndEconomicVariance() {
        var service = new ReportingService();

        var performance = service.performance(
                "team-001",
                100,
                100,
                80,
                70,
                0.9,
                0.8,
                1200,
                0.85,
                0.75);

        var economic = new EconomicReportEvidence(
                "econ-002",
                "org-001",
                "work-001",
                "worker-001",
                START,
                END,
                1000,
                1200,
                120,
                40,
                "labor",
                "evidence-002");

        var dashboard = service.dashboard(
                List.of("work-current"),
                List.of("work-completed"),
                List.of("work-blocked"),
                new CapacitySnapshot(192, 128, 16, 192),
                new StaffingSnapshot(6, 4, 2, 2),
                performance,
                List.of("capacity risk"),
                List.of("equipment exception"),
                1200,
                1000d,
                List.of(economic),
                Map.of("output", 70d));

        assertTrue(dashboard.blocked());
        assertTrue(dashboard.overBudget());

        assertEquals(48, dashboard.remainingCapacity(), 0.0001);
        assertEquals(2, dashboard.currentStaffing());

        assertEquals(
                List.of("work-blocked"),
                dashboard.blockedWorkIds());

        assertEquals(
                200,
                dashboard.economicEvidence()
                        .get(0)
                        .costVariance());

        assertEquals(
                70d,
                dashboard.organizationalMetrics().get("output"));
    }

    @Test
    void dashboardIsReadModelAndDoesNotExposeMutableCollections() {
        var dashboard = new ManagementDashboard(
                List.of("current"),
                List.of("completed"),
                List.of("blocked"),
                10,
                2,
                new PerformanceSnapshot(
                        "team",
                        10,
                        10,
                        8,
                        7,
                        1,
                        1,
                        100,
                        1,
                        1),
                List.of("risk"),
                List.of("exception"),
                120,
                100d,
                List.of(),
                Map.of("metric", 1d));

        assertThrows(
                UnsupportedOperationException.class,
                () -> dashboard.currentWorkIds().add("x"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> dashboard.blockedWorkIds().add("x"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> dashboard.risks().add("x"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> dashboard.organizationalMetrics().put("x", 2d));
    }

    @Test
    void managementDashboardDoesNotConvertBlockedWorkIntoCompletion() {
        var dashboard = new ManagementDashboard(
                List.of("work-current"),
                List.of(),
                List.of("work-blocked"),
                0,
                1,
                new PerformanceSnapshot(
                        "team",
                        100,
                        100,
                        20,
                        0,
                        1,
                        0.5,
                        500,
                        0.5,
                        0),
                List.of(),
                List.of("capacity exhausted"),
                500,
                1000d,
                List.of(),
                Map.of());

        assertTrue(dashboard.blocked());
        assertTrue(dashboard.completedWorkIds().isEmpty());
        assertEquals(0, dashboard.remainingCapacity());
    }

    @Test
    void historicalReportEvidenceRemainsDistinctFromCurrentDashboardState() {
        var historicalReport = new WorkReport(
                "historical-001",
                "worker-001",
                "org-001",
                WorkReport.ReportType.PERFORMANCE,
                START,
                END,
                "historical observation",
                "evidence-historical",
                END);

        var laterReport = new WorkReport(
                "later-001",
                "worker-001",
                "org-001",
                WorkReport.ReportType.PERFORMANCE,
                END,
                END.plusSeconds(3600),
                "later observation",
                "evidence-later",
                END.plusSeconds(3600));

        assertNotEquals(
                historicalReport.reportId(),
                laterReport.reportId());

        assertNotEquals(
                historicalReport.evidenceReference(),
                laterReport.evidenceReference());

        assertEquals(
                END,
                historicalReport.reportedAt());

        assertEquals(
                END.plusSeconds(3600),
                laterReport.reportedAt());
    }

    @Test
    void invalidEconomicEvidenceCannotBecomeOperationalTruth() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EconomicReportEvidence(
                        "econ-invalid",
                        "org-001",
                        "work-001",
                        "worker-001",
                        END,
                        START,
                        1000,
                        1200,
                        10,
                        10,
                        "labor",
                        "evidence"));
    }
}
