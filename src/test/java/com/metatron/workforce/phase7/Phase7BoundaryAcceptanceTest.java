package com.metatron.workforce.phase7;

import com.metatron.workforce.phase5.CapacitySnapshot;
import com.metatron.workforce.phase5.StaffingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Phase7BoundaryAcceptanceTest {

    private static final Instant START = Instant.parse("2026-08-20T08:00:00Z");
    private static final Instant END = Instant.parse("2026-08-20T16:00:00Z");

    @Test
    void coreManagementChainPreservesEvidenceAcrossReportingLayers() {
        var report = new WorkReport(
                "chain-report", "worker-001", "org-001", WorkReport.ReportType.PERFORMANCE,
                START, END, "completed 70 units from 100 planned",
                "execution-evidence-001", END, WorkReport.StatementNature.OBSERVED);

        var service = new ReportingService();
        var performance = service.performance(
                "team-001", 100, 90, 80, 70, 0.95, 0.90, 1200, 0.85, 0.75);
        var variance = service.variance("output", 100, 70);
        var economic = new EconomicReportEvidence(
                "chain-econ", report.organizationContextId(), "work-001", report.workerId(),
                report.periodStart(), report.periodEnd(), 1000, 1200, 120, 40,
                "labor + resource usage", report.evidenceReference());

        var dashboard = service.dashboard(
                List.of(), List.of("work-001"), List.of(),
                new CapacitySnapshot(192, 128, 16, 192),
                new StaffingSnapshot(1, 1, 1, 1), performance,
                List.of(), List.of(), economic.actualCost(), 1500d,
                List.of(economic), Map.of("outputVariance", variance.value()));

        assertEquals("execution-evidence-001", report.evidenceReference());
        assertEquals(-30, variance.value());
        assertEquals(200, economic.costVariance());
        assertEquals(200, dashboard.economicEvidence().get(0).costVariance());
        assertEquals(-30d, dashboard.organizationalMetrics().get("outputVariance"));
    }

    @Test
    void inferredAndEstimatedStatementsRemainDistinctFromObservedEvidence() {
        var observed = new WorkReport(
                "observed-001", "worker-001", "org-001", WorkReport.ReportType.PERFORMANCE,
                START, END, "direct observation", "evidence-observed", END,
                WorkReport.StatementNature.OBSERVED);
        var inferred = new WorkReport(
                "inferred-001", "worker-001", "org-001", WorkReport.ReportType.PERFORMANCE,
                START, END, "derived from observed evidence", "evidence-inferred", END,
                WorkReport.StatementNature.INFERRED);
        var estimated = new WorkReport(
                "estimated-001", "worker-001", "org-001", WorkReport.ReportType.ECONOMIC,
                START, END, "estimated allocation", "evidence-estimated", END,
                WorkReport.StatementNature.ESTIMATED);

        assertEquals(WorkReport.StatementNature.OBSERVED, observed.statementNature());
        assertEquals(WorkReport.StatementNature.INFERRED, inferred.statementNature());
        assertEquals(WorkReport.StatementNature.ESTIMATED, estimated.statementNature());
        assertNotEquals(observed.statementNature(), inferred.statementNature());
        assertNotEquals(observed.statementNature(), estimated.statementNature());
    }

    @Test
    void dashboardBudgetVisibilityDoesNotCreateBudgetAuthority() {
        var dashboard = new ManagementDashboard(
                List.of(), List.of(), List.of(), 20, 1,
                new PerformanceSnapshot("team", 10, 10, 10, 10, 1, 1, 120, 1, 1),
                List.of(), List.of(), 120, 100d, List.of(), Map.of());

        assertTrue(dashboard.overBudget());
        assertEquals(100d, dashboard.budget());
        assertEquals(120d, dashboard.reportedCost());
    }

    @Test
    void dashboardHasNoAuthorizationDecisionState() {
        var names = java.util.Arrays.stream(ManagementDashboard.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertFalse(names.contains("authorization"));
        assertFalse(names.contains("authorizationDecision"));
        assertFalse(names.contains("approved"));
        assertFalse(names.contains("authorized"));
    }

    @Test
    void recalculationDoesNotRewriteHistoricalReportEvidence() {
        var historical = new WorkReport(
                "historical-chain", "worker-001", "org-001", WorkReport.ReportType.PERFORMANCE,
                START, END, "70 completed", "evidence-historical-chain", END,
                WorkReport.StatementNature.OBSERVED);
        var service = new ReportingService();

        var first = service.dashboard(
                List.of(), List.of("work-001"), List.of(),
                new CapacitySnapshot(100, 80, 20, 100), new StaffingSnapshot(2, 2, 2, 2),
                service.performance("team", 100, 100, 70, 70, 1, 1, 100, 1, 1),
                List.of(), List.of(), 100, 150d, List.of(), Map.of("output", 70d));

        var recalculated = service.dashboard(
                List.of("work-002"), List.of(), List.of("work-002"),
                new CapacitySnapshot(100, 40, 20, 100), new StaffingSnapshot(2, 1, 1, 1),
                service.performance("team", 100, 100, 60, 50, 0.9, 0.8, 120, 0.8, 0.7),
                List.of("capacity changed"), List.of("new exception"), 120, 150d, List.of(),
                Map.of("output", 50d));

        assertEquals("historical-chain", historical.reportId());
        assertEquals("evidence-historical-chain", historical.evidenceReference());
        assertEquals(END, historical.reportedAt());
        assertNotEquals(first.currentWorkIds(), recalculated.currentWorkIds());
        assertNotEquals(first.completedWorkIds(), recalculated.completedWorkIds());
    }
}
