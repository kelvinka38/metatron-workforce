package com.metatron.workforce.phase11;

import com.metatron.workforce.phase10.Approval;
import com.metatron.workforce.phase10.CycleReport;
import com.metatron.workforce.phase10.EconomicSliceEvidence;
import com.metatron.workforce.phase10.ExecutionOutcome;
import com.metatron.workforce.phase10.FarmOperatingPlan;
import com.metatron.workforce.phase10.LearningImprovement;
import com.metatron.workforce.phase10.Phase10PrimaryVerticalSliceService;
import com.metatron.workforce.phase4.Delegation;
import com.metatron.workforce.phase6.AuthorizationDecision;
import com.metatron.workforce.phase6.AuthorizationRequest;
import com.metatron.workforce.phase6.AuthorizationService;
import com.metatron.workforce.phase6.Execution;
import com.metatron.workforce.phase6.ExecutionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class Phase11HardeningAcceptanceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void authorizationAllowedDeniedExpiredAndDelegatedPathsRemainExplicit() {
        AuthorizationService service = new AuthorizationService();
        AuthorizationRequest request = new AuthorizationRequest(
                "REQ-11-AUTH", "WORKER-1", "HEAD", "EXECUTE", "FARM-1",
                "ORG-1", "AUTH-1", "DEL-1", "POLICY-1", T0, T0, T0.plusSeconds(3600), "EVID-1");

        AuthorizationDecision allowed = service.resolve(request, T0.plusSeconds(1), "HEAD-1", r -> true);
        AuthorizationDecision denied = service.resolve(request, T0.plusSeconds(1), "HEAD-1", r -> false);
        AuthorizationDecision expired = service.resolve(request, T0.plusSeconds(3600), "HEAD-1", r -> true);

        assertEquals(AuthorizationDecision.Outcome.ALLOW, allowed.outcome());
        assertEquals(AuthorizationDecision.Outcome.DENY, denied.outcome());
        assertEquals(AuthorizationDecision.Outcome.DENY, expired.outcome());
        assertTrue(allowed.usableAt(T0.plusSeconds(1)));
        assertFalse(expired.usableAt(T0.plusSeconds(3600)));

        Delegation delegation = new Delegation("DEL-1", "HEAD-1", "WORKER-1", "AUTH-1",
                "FARM-1", "ORG-1", T0, T0.plusSeconds(3600), "HEAD-1", "EVID-DEL-1");
        assertTrue(delegation.activeAt(T0.plusSeconds(1)));
        assertFalse(delegation.activeAt(T0.plusSeconds(3600)));
    }

    @Test
    void executionRequiresMatchingCurrentAuthorizationAndPreservesRecoveryStates() {
        AuthorizationService authorizationService = new AuthorizationService();
        ExecutionService executionService = new ExecutionService();
        AuthorizationRequest request = new AuthorizationRequest(
                "REQ-11-EXEC", "WORKER-1", "HEAD", "EXECUTE", "FARM-1",
                "ORG-1", "AUTH-EXEC", "", "POLICY-1", T0, T0, T0.plusSeconds(3600), "EVID-EXEC");
        AuthorizationDecision allowed = authorizationService.resolve(request, T0.plusSeconds(1), "HEAD-1", r -> true);
        Execution requested = Execution.requested("EXEC-1", request.requestId(), "WORKER-1", "ASSIGN-1",
                allowed.authorizationId(), T0, "EVID-EXECUTION");

        Execution validating = executionService.validating(requested);
        Execution authorized = executionService.admit(validating, allowed, T0.plusSeconds(2));
        Execution blocked = executionService.start(authorized, T0.plusSeconds(3), e -> false);
        assertEquals(Execution.State.BLOCKED, blocked.state());

        Execution running = executionService.start(authorized, T0.plusSeconds(4), e -> true);
        Execution failed = executionService.fail(running, T0.plusSeconds(5), "downstream unavailable");
        assertEquals(Execution.State.FAILED, failed.state());
        assertTrue(failed.terminal());
        assertThrows(IllegalStateException.class,
                () -> executionService.complete(failed, T0.plusSeconds(6), "late success"));

        AuthorizationRequest other = new AuthorizationRequest(
                "REQ-11-OTHER", "WORKER-1", "HEAD", "EXECUTE", "FARM-1",
                "ORG-1", "AUTH-OTHER", "", "POLICY-1", T0, T0, T0.plusSeconds(3600), "EVID-OTHER");
        AuthorizationDecision otherDecision = authorizationService.resolve(other, T0.plusSeconds(1), "HEAD-1", r -> true);
        assertThrows(IllegalStateException.class,
                () -> executionService.admit(validating, otherDecision, T0.plusSeconds(2)));
    }

    @Test
    void primarySlicePreservesCapacityAndEconomicIntegrityUnderHardening() {
        Phase10PrimaryVerticalSliceService service = new Phase10PrimaryVerticalSliceService();
        FarmOperatingPlan plan = service.plan("PLAN-11", "FARM-11", 800, 100, 8,
                100_000, 2_000_000, 1_000, "100 workers", "resource risk", "G11-plan");
        Approval approval = service.approve(plan, "AUTHORITY:HEAD-11", "G11-approval");
        ExecutionOutcome outcome = service.execute(plan, approval, 800, 12_000_000,
                1_050, "G11-execution");
        CycleReport report = service.report(plan, outcome, "G11-report");
        EconomicSliceEvidence economic = service.economicEvidence(plan, outcome,
                20_000_000, 22_000_000, "labor + resource allocation", "G11-economic");

        assertEquals(0, plan.capacityDeficitHours());
        assertTrue(outcome.success());
        assertEquals("TARGET_MET", report.performance());
        assertTrue(economic.actualContributionEvidence() > economic.plannedContributionEvidence());
        assertFalse(report.provenance().isBlank());
    }

    @Test
    void failedEvidenceCannotBecomeValidatedLearning() {
        Phase10PrimaryVerticalSliceService service = new Phase10PrimaryVerticalSliceService();
        FarmOperatingPlan plan = service.plan("PLAN-11-FAIL", "FARM-11", 100, 10, 8,
                100_000, 0, 100, "10 workers", "none", "G11-plan");
        Approval approval = service.approve(plan, "AUTHORITY:HEAD-11", "G11-approval");
        ExecutionOutcome outcome = service.execute(plan, approval, 50, 5_000_000,
                50, "G11-execution");
        CycleReport report = service.report(plan, outcome, "G11-report");
        LearningImprovement learning = service.learn(report, "partial execution",
                "do not adopt incomplete result", true, "G11-learning");

        assertFalse(outcome.success());
        assertEquals("EXECUTION_FAILED", report.performance());
        assertFalse(learning.validated());
    }
}
