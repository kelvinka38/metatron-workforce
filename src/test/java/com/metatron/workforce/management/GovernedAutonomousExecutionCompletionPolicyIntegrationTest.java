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

/**
 * Proves runtime completion-policy selection through the REAL production Assignment-creation path --
 * GovernedAutonomousExecutionCapability.execute() -- not by hand-constructing an Assignment or calling
 * WorkforceCoreService.assign() directly. request.workSpec().completionPolicy() is what a real planner
 * (e.g. ExecutionWorkPlanner) would set based on the Objective's declared completion semantics; the
 * Worker/delegate capability here has no way to see or influence that value, proving it cannot downgrade
 * its own completion policy.
 */
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

        // Execution succeeded; COMPLETED must still be blocked with no release evidence at all.
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
        // A delegate that tries to claim success with whatever evidence it likes -- it has no
        // constructor/parameter/field through which it could ever influence CompletionPolicy, which is
        // fixed entirely by the caller's ExecutionWorkSpec before the delegate ever runs.
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                successfulDelegate(), core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(1));

        governed.execute(request("objective-locked", "step-locked", CompletionPolicy.PRODUCTION_REQUIRED));
        WorkforceCoreService.Assignment assignment = onlyAssignment(core);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, assignment.completionPolicy());

        // Even after a "restart"-style reconstruction of the Assignment view, the policy set at creation
        // time is immutable -- there is no code path in WorkforceCoreService that ever changes it.
        WorkforceCoreService.Assignment cancelled = core.transitionAssignment(
                assignment.assignmentId(), WorkforceCoreService.AssignmentStatus.CANCELLED);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, cancelled.completionPolicy());
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
