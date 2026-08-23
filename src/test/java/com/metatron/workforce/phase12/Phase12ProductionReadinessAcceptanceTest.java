package com.metatron.workforce.phase12;

import com.metatron.workforce.phase10.Approval;
import com.metatron.workforce.phase10.ExecutionOutcome;
import com.metatron.workforce.phase10.FarmOperatingPlan;
import com.metatron.workforce.phase10.Phase10PrimaryVerticalSliceService;
import com.metatron.workforce.phase10.VerticalSliceRequest;
import com.metatron.workforce.phase10.VerticalSliceResult;
import com.metatron.workforce.phase6.AuthorizationDecision;
import com.metatron.workforce.phase6.AuthorizationRequest;
import com.metatron.workforce.phase6.AuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Phase12ProductionReadinessAcceptanceTest {

    private final Phase10PrimaryVerticalSliceService service = new Phase10PrimaryVerticalSliceService();

    @Test
    void multiOrganizationWorkflowsRemainIsolated() {
        List<VerticalSliceResult> results = List.of(
                run("ORG-A", "DEPT-A", "TEAM-A", "WORKER-CLASS-A", "FARM-A"),
                run("ORG-B", "DEPT-B", "TEAM-B", "WORKER-CLASS-B", "FARM-B"),
                run("ORG-C", "DEPT-C", "TEAM-C", "WORKER-CLASS-C", "FARM-C"));

        assertEquals(3, results.size());
        assertEquals(Set.of("FARM-A", "FARM-B", "FARM-C"),
                results.stream().map(r -> r.plan().farmId()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(results.stream().allMatch(r -> r.approval().status() == Approval.Status.APPROVED));
        assertTrue(results.stream().allMatch(r -> r.execution().success()));

        for (VerticalSliceResult result : results) {
            assertTrue(result.request().requestId().contains("ORG-"));
            assertFalse(result.plan().provenance().contains("ORG-" + otherOrg(result.plan().farmId())));
        }
    }

    @Test
    void thousandWorkerCapacityIsRepresentableWithoutArtificialOverflow() {
        FarmOperatingPlan plan = service.plan("PLAN-1000", "FARM-SCALE", 8_000,
                1_000, 8, 100_000, 5_000_000, 10_000,
                "1,000 workers x 8h", "capacity test", "G12-scale");

        assertEquals(8_000, plan.availableLaborHours());
        assertEquals(0, plan.capacityDeficitHours());
        assertEquals(1_000, plan.workerCount());
    }

    @Test
    void concurrentWorkflowsPreserveIndependentExecutionEvidence() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<VerticalSliceResult>> tasks = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(i -> (Callable<VerticalSliceResult>) () -> run(
                            "ORG-" + (i % 4), "DEPT-" + (i % 8), "TEAM-" + (i % 16),
                            "CLASS-" + (i % 4), "FARM-" + i))
                    .toList();

            List<VerticalSliceResult> results = pool.invokeAll(tasks).stream()
                    .map(f -> {
                        try { return f.get(); }
                        catch (Exception e) { throw new AssertionError(e); }
                    }).toList();

            Set<String> executionIds = results.stream()
                    .map(r -> r.execution().executionId()).collect(java.util.stream.Collectors.toSet());
            assertEquals(64, executionIds.size());
            assertEquals(64, results.stream().filter(r -> r.execution().success()).count());
            assertTrue(results.stream().allMatch(r -> !r.execution().provenance().isBlank()));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failureRecoveryPreservesFailureAndDoesNotManufactureSuccess() {
        FarmOperatingPlan plan = service.plan("PLAN-RECOVERY", "FARM-R", 100,
                20, 8, 100_000, 1_000_000, 100,
                "20 workers", "service failure", "G12-recovery");
        Approval approval = service.approve(plan, "AUTHORITY:HEAD-R", "G12-approval");

        ExecutionOutcome failed = service.execute(plan, approval, 40, 4_000_000, 40, "G12-failure");
        assertFalse(failed.success());
        assertEquals("execution incomplete", failed.status());
        assertTrue(failed.failure().contains("required labor-hours"));

        var report = service.report(plan, failed, "G12-recovery-report");
        assertEquals("EXECUTION_FAILED", report.performance());
        assertEquals(40, report.actualLaborHours());
    }

    @Test
    void securityRejectsOutOfWindowAuthorization() {
        AuthorizationService service = new AuthorizationService();
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        AuthorizationRequest request = new AuthorizationRequest(
                "G12-SEC", "WORKER-SEC", "HEAD", "EXECUTE", "FARM-SEC", "ORG-SEC",
                "AUTH-SEC", "DEL-SEC", "POLICY-SEC", start, start, start.plusSeconds(60), "EVID-SEC");

        AuthorizationDecision decision = service.resolve(request, start.plusSeconds(60), "HEAD-SEC", r -> true);
        assertEquals(AuthorizationDecision.Outcome.DENY, decision.outcome());
        assertFalse(decision.usableAt(start.plusSeconds(60)));
    }

    @Test
    void economicEvidenceContainsPlanActualVarianceAndBoundary() {
        FarmOperatingPlan plan = service.plan("PLAN-ECON", "FARM-ECON", 80,
                10, 8, 100_000, 2_000_000, 500,
                "10 workers", "none", "G12-economic");
        Approval approval = service.approve(plan, "AUTHORITY:HEAD-E", "G12-approval");
        ExecutionOutcome execution = service.execute(plan, approval, 80, 9_000_000, 550, "G12-execution");
        var evidence = service.economicEvidence(plan, execution, 15_000_000, 16_500_000,
                "labor-hours + resource allocation", "G12-economic-evidence");

        assertEquals(15_000_000, evidence.plannedRevenueEvidence());
        assertEquals(16_500_000, evidence.actualRevenueEvidence());
        assertTrue(evidence.actualContributionEvidence() > evidence.plannedContributionEvidence());
        assertTrue(evidence.authorityBoundary().contains("Economy remains authoritative"));
        assertFalse(evidence.provenance().isBlank());
    }

    @Test
    void observabilityProvenanceExistsAcrossMaterialStages() {
        VerticalSliceResult result = run("ORG-OBS", "DEPT-OBS", "TEAM-OBS", "CLASS-OBS", "FARM-OBS");

        assertFalse(result.assignment().provenance().isBlank());
        assertFalse(result.plan().provenance().isBlank());
        assertFalse(result.approval().provenance().isBlank());
        assertFalse(result.execution().provenance().isBlank());
        assertFalse(result.report().provenance().isBlank());
        assertFalse(result.economicEvidence().provenance().isBlank());
        assertFalse(result.learning().provenance().isBlank());
    }

    private VerticalSliceResult run(String org, String department, String team,
            String workerClass, String farmId) {
        VerticalSliceRequest request = new VerticalSliceRequest(
                org + "-REQ-" + farmId,
                "HUMAN-" + org,
                "HEAD-WORKFORCE-" + org,
                "HEAD-FARM-" + farmId,
                "Operate " + department + "/" + team + "/" + workerClass,
                Instant.now());
        return service.run(request, farmId, 80, 10, 8,
                100_000, 1_000_000, 100, 110, 9_000_000,
                15_000_000, 16_000_000,
                "10 workers x 8h", "scale validation");
    }

    private String otherOrg(String farmId) {
        return switch (farmId) {
            case "FARM-A" -> "B";
            case "FARM-B" -> "C";
            default -> "A";
        };
    }
}
