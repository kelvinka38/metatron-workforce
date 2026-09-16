package com.metatron.workforce.release;

import java.time.Instant;
import java.util.List;

/**
 * Durable, monotonic release-evidence record binding one Assignment/Objective to the PR -> merge ->
 * deploy -> production-verification chain. Produced exclusively by {@link ReleaseEvidenceStore}, which
 * is the only code permitted to construct one of these outside this package; nothing accepts a
 * caller-supplied ReleaseEvidence as proof of anything.
 *
 * verificationHistory preserves an entry for every verification attempt (including failed ones) so a
 * later successful retry never silently erases the fact that an earlier attempt failed.
 */
public record ReleaseEvidence(
        String assignmentId,
        String objectiveRef,
        String repository,
        Integer prNumber,
        String prHeadSha,
        String baseBranch,
        String baseSha,
        String mergeSha,
        String deployedSha,
        String imageDigest,
        String expectedProductionSha,
        String observedProductionSha,
        VerificationStatus verificationStatus,
        Instant prRecordedAt,
        Instant mergeRecordedAt,
        Instant deployRecordedAt,
        Instant verificationRecordedAt,
        String prProducer,
        String mergeProducer,
        String deployProducer,
        String verificationProducer,
        List<String> verificationHistory) {

    public enum VerificationStatus { NONE, PASSED, FAILED }

    public ReleaseEvidence { verificationHistory = verificationHistory == null ? List.of() : List.copyOf(verificationHistory); }

    public static ReleaseEvidence empty(String assignmentId, String objectiveRef) {
        return new ReleaseEvidence(assignmentId, objectiveRef, null, null, null, null, null,
                null, null, null, null, null, VerificationStatus.NONE,
                null, null, null, null, null, null, null, null, List.of());
    }

    public boolean satisfiesPrRequired() {
        return repository != null && prNumber != null && prHeadSha != null;
    }

    /**
     * Full chain check for PRODUCTION_REQUIRED: PR evidence exists, a merge SHA was recorded, the
     * deployed SHA is exactly that merge SHA, and the CURRENT (latest) verification observed exactly
     * that SHA and passed. An earlier failed attempt in verificationHistory does not block a later pass.
     */
    public boolean satisfiesProductionRequired() {
        return satisfiesPrRequired()
                && mergeSha != null
                && deployedSha != null
                && deployedSha.equals(mergeSha)
                && expectedProductionSha != null
                && observedProductionSha != null
                && expectedProductionSha.equals(deployedSha)
                && observedProductionSha.equals(expectedProductionSha)
                && verificationStatus == VerificationStatus.PASSED;
    }
}
