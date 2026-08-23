package com.metatron.workforce.phase12;

import com.metatron.workforce.phase10.Approval;
import com.metatron.workforce.phase10.EconomicSliceEvidence;
import com.metatron.workforce.phase10.ExecutionOutcome;
import com.metatron.workforce.phase10.FarmOperatingPlan;
import com.metatron.workforce.phase10.Phase10PrimaryVerticalSliceService;
import com.metatron.workforce.phase10.VerticalSliceRequest;
import com.metatron.workforce.phase10.VerticalSliceResult;
import com.metatron.workforce.phase6.AuthorizationDecision;
import com.metatron.workforce.phase6.AuthorizationRequest;
import com.metatron.workforce.phase6.AuthorizationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime evidence producer for G12.
 *
 * This test intentionally emits observable execution records under build/ rather than
 * treating the existence of the acceptance test itself as evidence. The CI workflow
 * uploads the generated files with the G12 evidence bundle.
 */
class Phase12RuntimeEvidenceCaptureTest {

    private static final Path OUTPUT = Path.of("build", "g12-runtime-evidence", "runtime-evidence.ndjson");
    private final Phase10PrimaryVerticalSliceService service = new Phase10PrimaryVerticalSliceService();

    @Test
    void captureG12RuntimeEvidence() throws Exception {
        Instant started = Instant.now();
        List<String> records = new ArrayList<>();

        List<VerticalSliceResult> organizations = List.of(
                run("ORG-A", "DEPT-A", "TEAM-A", "CLASS-A", "FARM-A"),
                run("ORG-B", "DEPT-B", "TEAM-B", "CLASS-B", "FARM-B"),
                run("ORG-C", "DEPT-C", "TEAM-C", "CLASS-C", "FARM-C"));
        assertEquals(Set.of("FARM-A", "FARM-B", "FARM-C"),
                organizations.stream().map(r -> r.plan().farmId()).collect(Collectors.toSet()));
        records.add(json("scale.organization",
                "organizations", 3,
                "departments", 3,
                "teams", 3,
                "workerClasses", 3,
                "isolation", "PASS"));

        Instant concurrentStarted = Instant.now();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<VerticalSliceResult> concurrent;
        try {
            List<Callable<VerticalSliceResult>> tasks = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(i -> (Callable<VerticalSliceResult>) () ->
                            run("ORG-" + (i % 4), "DEPT-" + (i % 8), "TEAM-" + (i % 16),
                                    "CLASS-" + (i % 4), "FARM-" + i))
                    .toList();
            concurrent = pool.invokeAll(tasks).stream().map(f -> {
                try { return f.get(); }
                catch (Exception e) { throw new AssertionError(e); }
            }).toList();
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        long concurrentDurationMs = Duration.between(concurrentStarted, Instant.now()).toMillis();
        Set<String> executionIds = concurrent.stream()
                .map(r -> r.execution().executionId()).collect(Collectors.toSet());
        assertEquals(64, executionIds.size());
        assertEquals(64, concurrent.stream().filter(r -> r.execution().success()).count());
        records.add(json("scale.concurrent",
                "workflows", 64,
                "uniqueExecutionIds", executionIds.size(),
                "successfulExecutions", concurrent.stream().filter(r -> r.execution().success()).count(),
                "durationMs", concurrentDurationMs));

        FarmOperatingPlan capacity = service.plan("PLAN-1000", "FARM-SCALE", 8_000,
                1_000, 8, 100_000, 5_000_000, 10_000,
                "1,000 workers x 8h", "capacity test", "G12-runtime-capacity");
        assertEquals(8_000, capacity.availableLaborHours());
        assertEquals(0, capacity.capacityDeficitHours());
        records.add(json("capacity.1000-worker",
                "workerCount", capacity.workerCount(),
                "availableLaborHours", capacity.availableLaborHours(),
                "capacityDeficitHours", capacity.capacityDeficitHours()));

        FarmOperatingPlan recoveryPlan = service.plan("PLAN-RECOVERY", "FARM-R", 100,
                20, 8, 100_000, 1_000_000, 100,
                "20 workers", "service failure", "G12-runtime-recovery");
        Approval recoveryApproval = service.approve(recoveryPlan, "AUTHORITY:HEAD-R", "G12-runtime-approval");
        ExecutionOutcome failed = service.execute(recoveryPlan, recoveryApproval, 40, 4_000_000, 40, "G12-runtime-failure");
        assertFalse(failed.success());
        assertEquals("execution incomplete", failed.report());
        records.add(json("operations.failure-recovery",
                "executionId", failed.executionId(),
                "success", failed.success(),
                "report", failed.report(),
                "failure", failed.failure(),
                "actualLaborHours", failed.actualLaborHours(),
                "provenance", failed.provenance()));

        AuthorizationService authorizationService = new AuthorizationService();
        Instant authStart = Instant.parse("2026-01-01T00:00:00Z");
        AuthorizationRequest authRequest = new AuthorizationRequest(
                "G12-RUNTIME-SEC", "WORKER-SEC", "HEAD", "EXECUTE", "FARM-SEC", "ORG-SEC",
                "AUTH-SEC", "DEL-SEC", "POLICY-SEC", authStart, authStart,
                authStart.plusSeconds(60), "EVID-SEC");
        AuthorizationDecision denied = authorizationService.resolve(
                authRequest, authStart.plusSeconds(60), "HEAD-SEC", r -> true);
        assertEquals(AuthorizationDecision.Outcome.DENY, denied.outcome());
        records.add(json("security.authorization",
                "outcome", denied.outcome(),
                "usableAtExpiry", denied.usableAt(authStart.plusSeconds(60)),
                "authorizationId", denied.authorizationId(),
                "provenance", denied.provenance()));

        FarmOperatingPlan economicPlan = service.plan("PLAN-ECON", "FARM-ECON", 80,
                10, 8, 100_000, 2_000_000, 500,
                "10 workers", "none", "G12-runtime-economic");
        Approval economicApproval = service.approve(economicPlan, "AUTHORITY:HEAD-E", "G12-runtime-approval");
        ExecutionOutcome economicExecution = service.execute(economicPlan, economicApproval,
                80, 9_000_000, 550, "G12-runtime-execution");
        EconomicSliceEvidence economic = service.economicEvidence(economicPlan, economicExecution,
                15_000_000, 16_500_000, "labor-hours + resource allocation", "G12-runtime-economic-evidence");
        assertTrue(economic.actualContributionEvidence() > economic.plannedContributionEvidence());
        records.add(json("economic.plan-actual",
                "plannedRevenue", economic.plannedRevenueEvidence(),
                "actualRevenue", economic.actualRevenueEvidence(),
                "plannedContribution", economic.plannedContributionEvidence(),
                "actualContribution", economic.actualContributionEvidence(),
                "authorityBoundary", economic.authorityBoundary(),
                "provenance", economic.provenance()));

        VerticalSliceResult observed = run("ORG-OBS", "DEPT-OBS", "TEAM-OBS", "CLASS-OBS", "FARM-OBS");
        List<String> provenance = List.of(
                observed.assignment().provenance(),
                observed.plan().provenance(),
                observed.approval().provenance(),
                observed.execution().provenance(),
                observed.report().provenance(),
                observed.economicEvidence().provenance(),
                observed.learning().provenance());
        assertTrue(provenance.stream().allMatch(p -> p != null && !p.isBlank()));
        records.add(json("audit.provenance",
                "stages", List.of("assignment", "plan", "approval", "execution", "report", "economics", "learning"),
                "allStagesAttributed", true,
                "provenanceCount", provenance.size()));

        long durationMs = Duration.between(started, Instant.now()).toMillis();
        records.add(json("run.summary",
                "workflowEvidence", "G12 runtime evidence capture",
                "scenarioRecords", records.size(),
                "durationMs", durationMs,
                "generatedAt", Instant.now().toString()));

        Files.createDirectories(OUTPUT.getParent());
        Files.write(OUTPUT, records);
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
                "10 workers x 8h", "G12 runtime evidence");
    }

    private String json(String type, Object... fields) {
        StringBuilder json = new StringBuilder("{\"type\":").append(q(type));
        for (int i = 0; i < fields.length; i += 2) {
            json.append(',').append(q(String.valueOf(fields[i]))).append(':').append(value(fields[i + 1]));
        }
        return json.append('}').toString();
    }

    private String value(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof List<?> list) {
            return list.stream().map(this::value).collect(Collectors.joining(",", "[", "]"));
        }
        return q(String.valueOf(value));
    }

    private String q(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }
}
