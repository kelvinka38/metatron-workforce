package com.metatron.workforce.release;

import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.core.InMemoryWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseControlPlaneIntegrationTest {

    private static WorkforceCoreService.Assignment createAssignment(WorkforceCoreService s, String id, CompletionPolicy policy) {
        s.recognizeParticipant("P1", WorkforceCoreService.ParticipantType.AI, "ADMISSION-1");
        s.admitWorker("W1", "P1");
        s.participate("PART1", "W1", "ORG-GATEWAY", "POSITION-DIRECTOR", "ROLE-DIRECTOR");
        s.setAvailability("W1", true, 1.0);
        return s.assign(id, "OBJ-1", "W1", "PART1", "AUTHORITY-1", "AUTHZ-1", "integration test", policy);
    }

    @Test void persistenceRoundTripReproducesExactlyTheSameEvidenceAndMonotonicityStillHoldsAfterReload(@TempDir Path temp) {
        Path file = temp.resolve("release-evidence-state.json");
        ReleaseEvidenceStore first = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        first.recordPrPublished("A1", "OBJ-1", "kelvinka38/metatron-workforce", 449, "headSha1", "main", "baseSha1", "pr-producer");
        first.recordMerge("A1", "mergeSha1", "merge-producer");

        ReleaseEvidenceStore reloaded = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        ReleaseEvidence afterReload = reloaded.get("A1");
        assertNotNull(afterReload);
        assertEquals("mergeSha1", afterReload.mergeSha());
        assertEquals("headSha1", afterReload.prHeadSha());

        assertThrows(IllegalStateException.class, () -> reloaded.recordMerge("A1", "aDifferentMergeSha", "producer"));
    }

    @Test void restartBetweenReleaseStepsDoesNotLoseTheChain(@TempDir Path temp) {
        Path file = temp.resolve("release-evidence-state.json");
        new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file))
                .recordPrPublished("A2", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "pr-producer");

        ReleaseEvidenceStore afterFirstRestart = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        afterFirstRestart.recordMerge("A2", "mergeSha", "merge-producer");

        ReleaseEvidenceStore afterSecondRestart = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        afterSecondRestart.recordDeployment("A2", "mergeSha", "digest", "deploy-producer");

        ReleaseEvidenceStore afterThirdRestart = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        ReleaseEvidence verified = afterThirdRestart.recordVerification(
                "A2", "mergeSha", "mergeSha", ReleaseEvidence.VerificationStatus.PASSED, "verify-producer");

        assertTrue(verified.satisfiesProductionRequired());

        ReleaseEvidenceStore finalRead = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        assertTrue(finalRead.get("A2").satisfiesProductionRequired());
    }

    @Test void crossPrProvenanceAttackIsBlocked() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        evidence.recordPrPublished("X", "OBJ-X", "org/repo-A", 1, "headShaForA", "main", "baseSha", "pr-producer");
        ReleaseControlService release = new ReleaseControlService(evidence,
                MergeExecutor.UNAVAILABLE,
                (repository, prNumber) -> new PullRequestStateReader.PullRequestState("headShaForA", true, true),
                DeploymentAdapter.UNAVAILABLE);

        var outcome = release.requestMerge(new MergeAuthorization("X", "org/repo-B", 2, "headShaForB"));

        assertFalse(outcome.merged());
        assertEquals("merge_authorization_does_not_match_recorded_pr_evidence", outcome.blockedReason());
        assertNull(evidence.get("X").mergeSha());
    }

    @Test void requestMergeWithNoRecordedEvidenceAtAllIsBlockedRatherThanTrustingTheCaller() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        ReleaseControlService release = new ReleaseControlService(evidence);
        var outcome = release.requestMerge(new MergeAuthorization("never-recorded", "org/repo", 1, "anySha"));
        assertFalse(outcome.merged());
        assertEquals("no_recorded_pr_evidence_for_assignment", outcome.blockedReason());
    }

    @Test void verificationRetryAfterFailurePreservesHistoryAndLaterPassSatisfiesProductionRequired() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        evidence.recordPrPublished("A3", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "pr-producer");
        evidence.recordMerge("A3", "mergeSha", "merge-producer");
        evidence.recordDeployment("A3", "mergeSha", "digest", "deploy-producer");

        ReleaseEvidence failed = evidence.recordVerification("A3", "mergeSha", "staleObservedSha",
                ReleaseEvidence.VerificationStatus.PASSED, "verify-producer-1");
        assertEquals(ReleaseEvidence.VerificationStatus.FAILED, failed.verificationStatus());
        assertEquals(1, failed.verificationHistory().size());
        assertFalse(failed.satisfiesProductionRequired());

        ReleaseEvidence passed = evidence.recordVerification("A3", "mergeSha", "mergeSha",
                ReleaseEvidence.VerificationStatus.PASSED, "verify-producer-2");
        assertEquals(ReleaseEvidence.VerificationStatus.PASSED, passed.verificationStatus());
        assertEquals(2, passed.verificationHistory().size(), "the earlier failed attempt must remain in history");
        assertTrue(passed.verificationHistory().get(0).startsWith("FAILED"));
        assertTrue(passed.verificationHistory().get(1).startsWith("PASSED"));
        assertTrue(passed.satisfiesProductionRequired());

        assertThrows(IllegalStateException.class, () -> evidence.recordVerification("A3", "mergeSha", "somethingElse",
                ReleaseEvidence.VerificationStatus.PASSED, "verify-producer-3"),
                "PASSED must be terminal: cannot be changed once reached");
    }

    @Test void fullProductionRequiredLifecycleThroughTheRealWiredCompositionSurvivingRestarts(@TempDir Path temp) {
        Path file = temp.resolve("release-evidence-state.json");

        ReleaseEvidenceStore store1 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        WorkforceCoreService core1 = new WorkforceCoreService(
                new InMemoryWorkforceCoreStateStore(), new ReleaseEvidenceCompletionGate(store1));
        WorkforceCoreService.Assignment assignment = createAssignment(core1, "PROD-A", CompletionPolicy.PRODUCTION_REQUIRED);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, assignment.completionPolicy());

        assertThrows(IllegalStateException.class,
                () -> core1.transitionAssignment("PROD-A", WorkforceCoreService.AssignmentStatus.COMPLETED));

        store1.recordPrPublished("PROD-A", "OBJ-1", "kelvinka38/metatron-workforce", 449,
                "headShaFinal", "main", "baseShaFinal", "direct-coding:pr-publish");

        ReleaseEvidenceStore store2 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        ReleaseControlService release2 = new ReleaseControlService(store2,
                (authorization) -> MergeExecutor.MergeOutcome.merged("mergeShaFinal"),
                (repository, prNumber) -> new PullRequestStateReader.PullRequestState("headShaFinal", true, true),
                DeploymentAdapter.UNAVAILABLE);
        var mergeOutcome = release2.requestMerge(new MergeAuthorization(
                "PROD-A", "kelvinka38/metatron-workforce", 449, "headShaFinal"));
        assertTrue(mergeOutcome.merged());
        assertEquals("mergeShaFinal", mergeOutcome.mergeSha());

        ReleaseEvidenceStore store3 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        store3.recordDeployment("PROD-A", "mergeShaFinal", "img-digest-final", "deploy-producer");
        store3.recordVerification("PROD-A", "mergeShaFinal", "mergeShaFinal",
                ReleaseEvidence.VerificationStatus.PASSED, "verify-producer");

        ReleaseEvidenceStore store4 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        WorkforceCoreService core2 = new WorkforceCoreService(
                new InMemoryWorkforceCoreStateStore(), new ReleaseEvidenceCompletionGate(store4));
        createAssignment(core2, "PROD-A", CompletionPolicy.PRODUCTION_REQUIRED);
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                core2.transitionAssignment("PROD-A", WorkforceCoreService.AssignmentStatus.COMPLETED).status());
    }
}
