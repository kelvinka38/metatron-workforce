package com.metatron.workforce.release;

import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.core.InMemoryWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the REAL object graph WorkforceCoreConfiguration wires in production -- a durable, file-backed
 * ReleaseEvidenceStore shared by ReleaseControlService and ReleaseEvidenceCompletionGate, feeding a real
 * WorkforceCoreService -- not just isolated constructor-level tests. Also proves persistence survives a
 * simulated restart (a fresh ReleaseEvidenceStore instance reloading the same backing file) both on its
 * own and in the middle of a release-evidence chain, and proves the cross-PR/cross-repository provenance
 * attack the review flagged is actually blocked.
 */
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

        // Simulate a restart: brand new store instance, same backing file.
        ReleaseEvidenceStore reloaded = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        ReleaseEvidence afterReload = reloaded.get("A1");
        assertNotNull(afterReload);
        assertEquals("mergeSha1", afterReload.mergeSha());
        assertEquals("headSha1", afterReload.prHeadSha());

        // Monotonicity still enforced against the reloaded state, not reset by the restart.
        assertThrows(IllegalStateException.class, () -> reloaded.recordMerge("A1", "aDifferentMergeSha", "producer"));
    }

    @Test void restartBetweenReleaseStepsDoesNotLoseTheChain(@TempDir Path temp) {
        Path file = temp.resolve("release-evidence-state.json");
        new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file))
                .recordPrPublished("A2", "OBJ-1", "repo", 1, "headSha", "main", "baseSha", "pr-producer");

        // "Restart" before merge.
        ReleaseEvidenceStore afterFirstRestart = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        afterFirstRestart.recordMerge("A2", "mergeSha", "merge-producer");

        // "Restart" before deploy.
        ReleaseEvidenceStore afterSecondRestart = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        afterSecondRestart.recordDeployment("A2", "mergeSha", "digest", "deploy-producer");

        // "Restart" before verification.
        ReleaseEvidenceStore afterThirdRestart = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        ReleaseEvidence verified = afterThirdRestart.recordVerification(
                "A2", "mergeSha", "mergeSha", ReleaseEvidence.VerificationStatus.PASSED, "verify-producer");

        assertTrue(verified.satisfiesProductionRequired());

        // Final "restart" reads back the fully-passed chain unchanged.
        ReleaseEvidenceStore finalRead = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        assertTrue(finalRead.get("A2").satisfiesProductionRequired());
    }

    @Test void crossPrProvenanceAttackIsBlocked() {
        ReleaseEvidenceStore evidence = new ReleaseEvidenceStore();
        // Assignment X has recorded evidence for repo/PR "A".
        evidence.recordPrPublished("X", "OBJ-X", "org/repo-A", 1, "headShaForA", "main", "baseSha", "pr-producer");
        ReleaseControlService release = new ReleaseControlService(evidence,
                MergeExecutor.UNAVAILABLE, // would blow up loudly if ever reached -- it must not be
                (repository, prNumber) -> new PullRequestStateReader.PullRequestState("headShaForA", true, true),
                DeploymentAdapter.UNAVAILABLE);

        // Attacker/misconfigured caller submits an authorization for a DIFFERENT repo/PR against the
        // SAME assignment id whose evidence is actually for repo-A/PR-1.
        var outcome = release.requestMerge(new MergeAuthorization("X", "org/repo-B", 2, "headShaForB"));

        assertFalse(outcome.merged());
        assertEquals("merge_authorization_does_not_match_recorded_pr_evidence", outcome.blockedReason());
        // No merge evidence must have been recorded for X as a side effect of the blocked attempt.
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

        // Step 1: the exact object graph WorkforceCoreConfiguration builds.
        ReleaseEvidenceStore store1 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        WorkforceCoreService core1 = new WorkforceCoreService(
                new InMemoryWorkforceCoreStateStore(), new ReleaseEvidenceCompletionGate(store1));
        WorkforceCoreService.Assignment assignment = createAssignment(core1, "PROD-A", CompletionPolicy.PRODUCTION_REQUIRED);
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, assignment.completionPolicy());

        // Execution succeeds, but COMPLETED must be blocked with no release evidence at all.
        assertThrows(IllegalStateException.class,
                () -> core1.transitionAssignment("PROD-A", WorkforceCoreService.AssignmentStatus.COMPLETED));

        // Record the governed PR evidence.
        store1.recordPrPublished("PROD-A", "OBJ-1", "kelvinka38/metatron-workforce", 449,
                "headShaFinal", "main", "baseShaFinal", "direct-coding:pr-publish");

        // Simulate a restart between PR evidence and merge: fresh store, fresh gate, fresh WorkforceCoreService.
        ReleaseEvidenceStore store2 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        ReleaseControlService release2 = new ReleaseControlService(store2,
                (authorization) -> MergeExecutor.MergeOutcome.merged("mergeShaFinal"),
                (repository, prNumber) -> new PullRequestStateReader.PullRequestState("headShaFinal", true, true),
                DeploymentAdapter.UNAVAILABLE);
        var mergeOutcome = release2.requestMerge(new MergeAuthorization(
                "PROD-A", "kelvinka38/metatron-workforce", 449, "headShaFinal"));
        assertTrue(mergeOutcome.merged());
        assertEquals("mergeShaFinal", mergeOutcome.mergeSha());

        // Simulate another restart between merge and deploy/verify.
        ReleaseEvidenceStore store3 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        store3.recordDeployment("PROD-A", "mergeShaFinal", "img-digest-final", "deploy-producer");
        store3.recordVerification("PROD-A", "mergeShaFinal", "mergeShaFinal",
                ReleaseEvidence.VerificationStatus.PASSED, "verify-producer");

        // Final restart, and COMPLETED now succeeds through the same WorkforceCoreConfiguration-shaped wiring.
        ReleaseEvidenceStore store4 = new ReleaseEvidenceStore(new FileReleaseEvidenceStateStore(file));
        WorkforceCoreService core2 = new WorkforceCoreService(
                new InMemoryWorkforceCoreStateStore(), new ReleaseEvidenceCompletionGate(store4));
        createAssignment(core2, "PROD-A", CompletionPolicy.PRODUCTION_REQUIRED);
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                core2.transitionAssignment("PROD-A", WorkforceCoreService.AssignmentStatus.COMPLETED).status());
    }
}
