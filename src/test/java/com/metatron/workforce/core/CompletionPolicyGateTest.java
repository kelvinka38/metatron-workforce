package com.metatron.workforce.core;

import com.metatron.workforce.release.ReleaseControlService;
import com.metatron.workforce.release.ReleaseEvidence;
import com.metatron.workforce.release.ReleaseEvidenceCompletionGate;
import com.metatron.workforce.release.ReleaseEvidenceStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompletionPolicyGateTest {

    private static WorkforceCoreService coreWithGate(ReleaseEvidenceStore evidence) {
        return new WorkforceCoreService(new InMemoryWorkforceCoreStateStore(), new ReleaseEvidenceCompletionGate(evidence));
    }

    private static WorkforceCoreService.Assignment createAssignment(WorkforceCoreService s, String id, CompletionPolicy policy) {
        s.recognizeParticipant("P1", WorkforceCoreService.ParticipantType.AI, "ADMISSION-1");
        s.admitWorker("W1", "P1");
        s.participate("PART1", "W1", "ORG-GATEWAY", "POSITION-DIRECTOR", "ROLE-DIRECTOR");
        s.setAvailability("W1", true, 1.0);
        return s.assign(id, "OBJ-1", "W1", "PART1", "AUTHORITY-1", "AUTHZ-1", "release-plane test", policy);
    }

    @Test void executionRequiredCompletesAfterSuccessWithNoEvidence() {
        WorkforceCoreService s = coreWithGate(new ReleaseEvidenceStore());
        createAssignment(s, "A1", CompletionPolicy.EXECUTION_REQUIRED);
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                s.transitionAssignment("A1", WorkforceCoreService.AssignmentStatus.COMPLETED).status());
    }

    @Test void prRequiredCannotCompleteWithoutPrEvidence() {
        WorkforceCoreService s = coreWithGate(new ReleaseEvidenceStore());
        createAssignment(s, "A2", CompletionPolicy.PR_REQUIRED);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.transitionAssignment("A2", WorkforceCoreService.AssignmentStatus.COMPLETED));
        assertTrue(ex.getMessage().contains("completion-evidence-required"));
        assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE, s.assignments("W1").stream()
                .filter(a -> a.assignmentId().equals("A2")).findFirst().orElseThrow().status());
    }

    @Test void prRequiredCompletesOnceValidPrEvidenceIsRecorded() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService s = coreWithGate(evidence);
        createAssignment(s, "A3", CompletionPolicy.PR_REQUIRED);
        evidence.recordPrPublished("A3", "OBJ-1", "kelvinka38/metatron-workforce", 448, "abc123headsha",
                "main", "def456basesha", "direct-coding:pr-publish");
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                s.transitionAssignment("A3", WorkforceCoreService.AssignmentStatus.COMPLETED).status());
    }

    @Test void productionRequiredCannotCompleteAfterExecutionSuccessAlone() {
        WorkforceCoreService s = coreWithGate(new ReleaseEvidenceStore());
        createAssignment(s, "A4", CompletionPolicy.PRODUCTION_REQUIRED);
        assertThrows(IllegalStateException.class,
                () -> s.transitionAssignment("A4", WorkforceCoreService.AssignmentStatus.COMPLETED));
    }

    @Test void productionRequiredCannotCompleteWithPrEvidenceOnlyNoDeployOrVerification() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService s = coreWithGate(evidence);
        createAssignment(s, "A5", CompletionPolicy.PRODUCTION_REQUIRED);
        evidence.recordPrPublished("A5", "OBJ-1", "kelvinka38/metatron-workforce", 448, "shaA", "main", "shaBase", "producer");
        assertThrows(IllegalStateException.class,
                () -> s.transitionAssignment("A5", WorkforceCoreService.AssignmentStatus.COMPLETED));
    }

    @Test void productionRequiredCompletesOnlyAfterFullValidEvidenceChain() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService s = coreWithGate(evidence);
        createAssignment(s, "A6", CompletionPolicy.PRODUCTION_REQUIRED);
        evidence.recordPrPublished("A6", "OBJ-1", "kelvinka38/metatron-workforce", 448, "headSha1", "main", "baseSha1", "pr-producer");
        evidence.recordMerge("A6", "mergeSha1", "merge-producer");
        evidence.recordDeployment("A6", "mergeSha1", "img-digest-1", "deploy-producer");
        evidence.recordVerification("A6", "mergeSha1", "mergeSha1", ReleaseEvidence.VerificationStatus.PASSED, "verify-producer");
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                s.transitionAssignment("A6", WorkforceCoreService.AssignmentStatus.COMPLETED).status());
    }

    @Test void deployedShaMustEqualRecordedMergeShaOrDeploymentIsRejected() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        evidence.recordPrPublished("A7", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "producer");
        evidence.recordMerge("A7", "realMergeSha", "producer");
        assertThrows(IllegalStateException.class, () -> evidence.recordDeployment("A7", "unrelatedSha", "digest", "producer"));
    }

    @Test void observedProductionShaMismatchFailsVerificationEvenIfCallerReportsPassed() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        evidence.recordPrPublished("A8", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "producer");
        evidence.recordMerge("A8", "mergeSha", "producer");
        evidence.recordDeployment("A8", "mergeSha", "digest", "producer");
        ReleaseEvidence result = evidence.recordVerification("A8", "mergeSha", "differentObservedSha",
                ReleaseEvidence.VerificationStatus.PASSED, "producer");
        assertEquals(ReleaseEvidence.VerificationStatus.FAILED, result.verificationStatus());
        assertFalse(result.satisfiesProductionRequired());
    }

    @Test void evidenceIsMonotonicAndCannotBeRewrittenToADifferentMergeSha() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        evidence.recordPrPublished("A9", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "producer");
        evidence.recordMerge("A9", "firstMergeSha", "producer");
        assertThrows(IllegalStateException.class, () -> evidence.recordMerge("A9", "differentMergeSha", "producer"));
        assertEquals("firstMergeSha", evidence.recordMerge("A9", "firstMergeSha", "producer").mergeSha());
    }

    @Test void retryingPrPublishedWithIdenticalDataIsIdempotent() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        ReleaseEvidence first = evidence.recordPrPublished("A10", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "producer");
        ReleaseEvidence second = evidence.recordPrPublished("A10", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "producer");
        assertEquals(first.prRecordedAt(), second.prRecordedAt());
    }

    @Test void reconcileTerminalExecutionCapacityCannotBypassCompletionPolicyBecauseItUsesTheSameMethod() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService s = coreWithGate(evidence);
        createAssignment(s, "A11", CompletionPolicy.PRODUCTION_REQUIRED);
        assertThrows(IllegalStateException.class,
                () -> s.transitionAssignment("A11", WorkforceCoreService.AssignmentStatus.COMPLETED));
    }

    @Test void directControllerStyleTransitionCannotBypassCompletionPolicyEither() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService s = coreWithGate(evidence);
        createAssignment(s, "A12", CompletionPolicy.PR_REQUIRED);
        assertThrows(IllegalStateException.class,
                () -> s.transitionAssignment("A12", WorkforceCoreService.AssignmentStatus.COMPLETED));
    }

    @Test void completionPolicyHasNoSetterAndSurvivesTransitionUnchanged() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        WorkforceCoreService s = coreWithGate(evidence);
        WorkforceCoreService.Assignment created = createAssignment(s, "A13", CompletionPolicy.PRODUCTION_REQUIRED);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, created.completionPolicy());
        WorkforceCoreService.Assignment cancelled = s.transitionAssignment("A13", WorkforceCoreService.AssignmentStatus.CANCELLED);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, cancelled.completionPolicy());
    }

    @Test void defaultUnwiredGateDeniesNonExecutionPoliciesFailClosed() {
        WorkforceCoreService s = new WorkforceCoreService();
        createAssignment(s, "A14", CompletionPolicy.PR_REQUIRED);
        assertThrows(IllegalStateException.class,
                () -> s.transitionAssignment("A14", WorkforceCoreService.AssignmentStatus.COMPLETED));
    }

    @Test void existingSevenArgAssignOverloadDefaultsToExecutionRequiredUnchangedBehavior() {
        WorkforceCoreService s = new WorkforceCoreService();
        s.recognizeParticipant("P1", WorkforceCoreService.ParticipantType.AI, "ADMISSION-1");
        s.admitWorker("W1", "P1");
        s.participate("PART1", "W1", "ORG-GATEWAY", "POSITION-DIRECTOR", "ROLE-DIRECTOR");
        s.setAvailability("W1", true, 1.0);
        WorkforceCoreService.Assignment a = s.assign("A15", "OBJ-1", "W1", "PART1", "AUTHORITY-1", "AUTHZ-1", "legacy call site");
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, a.completionPolicy());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                s.transitionAssignment("A15", WorkforceCoreService.AssignmentStatus.COMPLETED).status());
    }

    @Test void releaseControlServiceRequestMergeBlocksOnStaleHeadWithoutInvokingExecutor() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        evidence.recordPrPublished("A16", "OBJ-1", "repo", 1, "originalHeadSha", "main", "baseSha", "producer");
        ReleaseControlService release = new ReleaseControlService(evidence,
                com.metatron.workforce.release.MergeExecutor.UNAVAILABLE,
                (repository, prNumber) -> new com.metatron.workforce.release.PullRequestStateReader.PullRequestState(
                        "differentHeadShaNow", true, true),
                com.metatron.workforce.release.DeploymentAdapter.UNAVAILABLE);
        var outcome = release.requestMerge(new com.metatron.workforce.release.MergeAuthorization(
                "A16", "repo", 1, "originalHeadSha"));
        assertFalse(outcome.merged());
        assertEquals("stale_pr_head", outcome.blockedReason());
    }

    @Test void releaseControlServiceRequestMergeBlocksOnFailedRequiredChecks() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        evidence.recordPrPublished("A17", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "producer");
        ReleaseControlService release = new ReleaseControlService(evidence,
                com.metatron.workforce.release.MergeExecutor.UNAVAILABLE,
                (repository, prNumber) -> new com.metatron.workforce.release.PullRequestStateReader.PullRequestState(
                        "headSha", false, true),
                com.metatron.workforce.release.DeploymentAdapter.UNAVAILABLE);
        var outcome = release.requestMerge(new com.metatron.workforce.release.MergeAuthorization("A17", "repo", 1, "headSha"));
        assertFalse(outcome.merged());
        assertEquals("required_checks_not_satisfied", outcome.blockedReason());
    }

    @Test void releaseControlServiceDefaultUnavailableExecutorsFailClosedForMergeAndDeploy() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        ReleaseControlService release = new ReleaseControlService(evidence);
        var deployOutcome = release.requestDeployment("A18", "anySha");
        assertFalse(deployOutcome.deployed());
        assertEquals("no_recorded_merge_evidence", deployOutcome.blockedReason());
    }
}
