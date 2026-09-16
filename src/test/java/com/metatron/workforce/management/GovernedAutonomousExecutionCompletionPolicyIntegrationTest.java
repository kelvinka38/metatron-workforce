package com.metatron.workforce.management;

import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.core.InMemoryWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.release.ReleaseEvidence;
import com.metatron.workforce.release.ReleaseEvidenceCompletionGate;
import com.metatron.workforce.release.ReleaseEvidenceStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GovernedAutonomousExecutionCompletionPolicyIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void ordinaryExecutionOnlyObjectiveDefaultsToExecutionRequiredAndCompletesBackwardCompatibly() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService core = coreWithGate(evidence);
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                successfulDelegate(), core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(1));

        var result = governed.execute(request("objective-exec", "step-exec", CompletionPolicy.EXECUTION_REQUIRED));

        assertTrue(result.success());
        WorkforceCoreService.Assignment assignment = onlyAssignment(core);
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, assignment.completionPolicy());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, assignment.status(),
                "execution-only work must remain backward-compatible: COMPLETED immediately on success");
    }

    @Test
    void prOnlyObjectiveCreatesPrRequiredAssignmentThroughTheRealPathAndBlocksUntilPrEvidenceExists() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService core = coreWithGate(evidence);
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                successfulDelegate(), core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(1));

        var result = governed.execute(request("objective-pr", "step-pr", CompletionPolicy.PR_REQUIRED));
        assertTrue(result.success(), "execution itself still succeeds");

        WorkforceCoreService.Assignment assignment = onlyAssignment(core);
        assertEquals(CompletionPolicy.PR_REQUIRED, assignment.completionPolicy(),
                "the real production path must have selected PR_REQUIRED from the ExecutionWorkSpec, not the Worker");
        assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE, assignment.status(),
                "execution success alone must NOT complete a PR_REQUIRED assignment");
        assertThrows(IllegalStateException.class,
                () -> core.transitionAssignment(assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.COMPLETED));

        evidence.recordPrPublished(assignment.assignmentId(), "objective-pr", "kelvinka38/metatron-workforce",
                453, "headSha", "main", "baseSha", "planner:pr-only-objective");

        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                core.transitionAssignment(assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.COMPLETED).status(),
                "valid PR evidence must now permit completion");
    }

    @Test
    void productionChangeObjectiveCreatesProductionRequiredAssignmentThroughTheRealPathAndBlocksUntilFullEvidence() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService core = coreWithGate(evidence);
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                successfulDelegate(), core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(1));

        var result = governed.execute(request("objective-prod", "step-prod", CompletionPolicy.PRODUCTION_REQUIRED));
        assertTrue(result.success());

        WorkforceCoreService.Assignment assignment = onlyAssignment(core);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, assignment.completionPolicy());
        assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE, assignment.status());

        assertThrows(IllegalStateException.class,
                () -> core.transitionAssignment(assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.COMPLETED));

        String id = assignment.assignmentId();
        evidence.recordPrPublished(id, "objective-prod", "kelvinka38/metatron-workforce", 453, "headSha", "main", "baseSha", "planner");
        evidence.recordMerge(id, "mergeSha", "release-control");
        evidence.recordDeployment(id, "mergeSha", "digest", "release-control");
        evidence.recordVerification(id, "mergeSha", "mergeSha", ReleaseEvidence.VerificationStatus.PASSED, "release-control");

        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                core.transitionAssignment(id, WorkforceCoreService.AssignmentStatus.COMPLETED).status(),
                "full release evidence must now permit completion");
    }

    @Test
    void workerCannotDowngradeCompletionPolicyBecauseTheDelegateHasNoWayToSetIt() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService core = coreWithGate(evidence);
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                successfulDelegate(), core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(1));

        governed.execute(request("objective-locked", "step-locked", CompletionPolicy.PRODUCTION_REQUIRED));
        WorkforceCoreService.Assignment assignment = onlyAssignment(core);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, assignment.completionPolicy());

        WorkforceCoreService.Assignment cancelled = core.transitionAssignment(
                assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.CANCELLED);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, cancelled.completionPolicy());
    }

    @Test
    void objectiveLevelCeilingSurvivesAllTheWayThroughTheRealAssignmentCreationPath() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService core = coreWithGate(evidence);
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                successfulDelegate(), core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(1));

        com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec plannerStep =
                new com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec(
                        "step-1", "do the production change", "target", "test.capability",
                        List.of(), com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.READ_ONLY,
                        List.of(), List.of());
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, plannerStep.completionPolicy());

        com.metatron.workforce.interaction.intelligence.NormalizedRequest objective =
                new com.metatron.workforce.interaction.intelligence.NormalizedRequest(
                        "ship the production change", "target", List.of(),
                        com.metatron.workforce.interaction.intelligence.IntelligenceDepth.FAST, "output",
                        List.of(), List.of(), "now", "",
                        com.metatron.workforce.interaction.intelligence.IntelligenceMode.EXECUTION,
                        com.metatron.workforce.interaction.intelligence.CollaborationMode.SINGLE, List.of(),
                        com.metatron.workforce.interaction.intelligence.DeterministicCapability.NONE, false,
                        null, null, "")
                .withCompletionPolicy(CompletionPolicy.PRODUCTION_REQUIRED)
                .withExecutionWorkPlan(List.of(plannerStep));

        var result = governed.execute(new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", "objective-ceiling-e2e", objective.executionWorkPlan().get(0)));

        assertTrue(result.success());
        WorkforceCoreService.Assignment assignment = onlyAssignment(core);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, assignment.completionPolicy(),
                "the Objective's ceiling, not the planner's under-declared step, must be what reaches the real Assignment");
        assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE, assignment.status());
    }

    private static WorkforceCoreService coreWithGate(ReleaseEvidenceStore evidence) {
        WorkforceCoreService core = new WorkforceCoreService(
                new InMemoryWorkforceCoreStateStore(), new ReleaseEvidenceCompletionGate(evidence));
        core.recognizeParticipant("participant-worker-a", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker-a", "participant-worker-a");
        core.participate("participation-worker-a", "worker-a", "org-metatron", "position", "role");
        core.attestCapability("worker-a", "test.capability", 1.0, "evidence:capability");
        core.setAvailability("worker-a", true, 1.0);
        return core;
    }

    private static WorkforceCoreService.Assignment onlyAssignment(WorkforceCoreService core) {
        return core.allAssignments().stream().findFirst().orElseThrow();
    }

    private static AutonomousExecutionCapability.CapabilityRequest request(String objectiveId, String stepId, CompletionPolicy policy) {
        return new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", objectiveId,
                new ExecutionWorkSpec(stepId, "Execute " + stepId, "target", "test.capability", List.of(),
                        ExecutionWorkSpec.Consequence.READ_ONLY, List.of(), List.of(), policy));
    }

    private static AutonomousExecutionCapability successfulDelegate() {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.capability"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public boolean supportsWorker(String candidate) { return "worker-a".equals(candidate); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work-" + request.workSpec().stepId(), List.of("evidence:work"), "PASS");
            }
        };
    }
}
