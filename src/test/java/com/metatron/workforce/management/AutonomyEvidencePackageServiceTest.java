package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.workplace.InMemoryWorkplaceContinuityStateStore;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomyEvidencePackageServiceTest {
    @Test
    void reconstructsCanonicalProgressAndEvidenceClosedCompletionPackage() {
        Instant t0 = Instant.parse("2026-08-31T07:00:00Z");
        String objectiveId = "objective-evidence";
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-a", "Audit repository", "repo", "test.read", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("repository audit is complete"), List.of("repository evidence"));

        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptHumanObjective(objectiveId, "worker-head", "org-metatron", "Evidence package test",
                "human:founder", "admission:founder", "case-evidence", "conversation-evidence",
                "message-evidence", "telegram", request(step), t0);
        ManagementLease lease = management.acquireManagementLease(objectiveId, "runner-a",
                Duration.ofMinutes(5), t0.plusSeconds(1)).orElseThrow();
        management.beginPlanning(objectiveId, "runner-a", lease.token(), t0.plusSeconds(2));
        management.recordPlan(objectiveId, "runner-a", lease.token(), List.of(step), t0.plusSeconds(3));
        management.beginExecution(objectiveId, "runner-a", lease.token(), t0.plusSeconds(4));

        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        DurableWorkGraph graph = coordination.ensureGraph(objectiveId, List.of(step), t0.plusSeconds(3));
        DurableDispatch dispatch = coordination.beginDispatch(objectiveId, graph.graphVersion(), "step-a", t0.plusSeconds(4));
        coordination.completeDispatch(dispatch.dispatchId(), List.of("evidence:repository"), t0.plusSeconds(5));
        coordination.completeGraph(objectiveId, graph.graphVersion(), t0.plusSeconds(6));

        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant-a", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker-a", "participant-a");
        core.participate("participation-a", "worker-a", "org-metatron", "position-a", "role-a");
        core.attestCapability("worker-a", "test.read", 1.0, "evidence:capability");
        core.setAvailability("worker-a", true, 1.0);
        core.reserveCapacity("reservation-a", "assignment-a", objectiveId, "worker-a", 1.0);
        core.assignReserved("reservation-a", "participation-a", "authority:a", "authorization:a", "Audit repository");
        core.transitionAssignment("assignment-a", WorkforceCoreService.AssignmentStatus.COMPLETED);
        management.addAssignmentReference(objectiveId, "worker-head", "assignment-a", t0.plusSeconds(6));

        ExecutionAttemptService attempts = new ExecutionAttemptService();
        var attempt = attempts.begin(dispatch.dispatchId(), objectiveId, "step-a", "worker-a", "assignment-a",
                "authorization:a", "runtime-a", 1, Duration.ofMinutes(1), t0.plusSeconds(4));
        attempts.succeed(attempt.attemptId(), attempt.fencingToken(), t0.plusSeconds(5));

        ObservationClosureService observation = new ObservationClosureService();
        var requirement = observation.ensureRequirements(objectiveId, List.of(step), t0.plusSeconds(5)).getFirst();
        observation.recordReport(new ObservationReport(
                "report-a", requirement.requirementId(), objectiveId, "repo", "audit complete",
                "independent-test", t0.plusSeconds(7), t0.plusSeconds(7),
                List.of("evidence:repository"), 1.0, ObservationReport.Quality.HIGH, "",
                ObservationReport.CriterionResult.PASS));

        AutonomySafetyService safety = new AutonomySafetyService();
        safety.ensureObjective(objectiveId, t0);
        safety.reserveDispatch(objectiveId, dispatch.dispatchId(), 1, "authority:a",
                ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, t0.plusSeconds(4));

        WorkplaceContinuityService workplace = new WorkplaceContinuityService(
                new InMemoryWorkplaceContinuityStateStore(), management, List.of(), List.of());
        workplace.bindAcceptedObjective(objectiveId, "founder", "conversation-evidence", "telegram",
                "message-evidence", "admission:founder", t0);
        workplace.referenceMeeting(objectiveId, "meeting:1", t0.plusSeconds(7));
        workplace.referenceDecision(objectiveId, "decision:1", t0.plusSeconds(8));
        workplace.referenceEvidence(objectiveId, "evidence:repository", t0.plusSeconds(9));

        management.recordStepCompleted(objectiveId, "runner-a", lease.token(), "step-a",
                List.of("evidence:repository", "observation-report:report-a"), t0.plusSeconds(9));
        management.completeAutonomousObjective(objectiveId, "runner-a", lease.token(), t0.plusSeconds(10));

        AutonomyEvidencePackageService service = new AutonomyEvidencePackageService(
                management, coordination, safety, observation, core, attempts, workplace);
        var pkg = service.packageFor(objectiveId);

        assertEquals(ManagementObjective.Status.COMPLETED, pkg.objective().status());
        assertNotNull(pkg.work());
        assertEquals(AutonomousObjectiveWork.Status.COMPLETED, pkg.work().status());
        assertEquals(DurableWorkGraph.Status.COMPLETED, pkg.graphHistory().getLast().status());
        assertEquals(DurableDispatch.Status.SUCCEEDED, pkg.dispatches().getFirst().status());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, pkg.assignments().getFirst().status());
        assertEquals(List.of("worker-a"), pkg.workerIds());
        assertTrue(pkg.executionAttempts().stream().allMatch(a -> a.terminal()));
        assertEquals(ObservationClosureService.Verdict.PASSED, pkg.observationVerdict());
        assertNotNull(pkg.workplaceContinuity());
        assertFalse(pkg.managementHistory().isEmpty());
        assertFalse(pkg.managementOutbox().isEmpty());
        assertTrue(pkg.consumedCostUnits() > 0.0);
        assertTrue(pkg.durationMillis() > 0L);
        assertEquals("NONE_RECORDED_WITHIN_CURRENT_OPERATIONAL_SCOPE", pkg.remainingRisk());
        assertEquals("DELIVERY_LEARNING", pkg.nextTransition());
        assertTrue(pkg.evidenceClosed());
    }

    private static NormalizedRequest request(ExecutionWorkSpec step) {
        return new NormalizedRequest("Audit repository", "repo", List.of(), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of(), "current", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
