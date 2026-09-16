package com.metatron.workforce.release;

import java.time.Instant;

/**
 * Durable, monotonic release-evidence record binding one Assignment/Objective to the PR -> merge ->
 * deploy -> production-verification chain. Produced exclusively by {@link ReleaseEvidenceStore}, which
 * is the only code permitted to construct one of these outside this package; nothing accepts a
 * caller-supplied ReleaseEvidence as proof of anything.
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
        String verificationProducer) {

    public enum VerificationStatus { NONE, PASSED, FAILED }

    public static ReleaseEvidence empty(String assignmentId, String objectiveRef) {
        return new ReleaseEvidence(assignmentId, objectiveRef, null, null, null, null, null,
                null, null, null, null, null, VerificationStatus.NONE,
                null, null, null, null, null, null, null, null);
    }

    public boolean satisfiesPrRequired() {
        return repository != null && prNumber != null && prHeadSha != null;
    }

    /**
     * Full chain check for PRODUCTION_REQUIRED: PR evidence exists, a merge SHA was recorded, the
     * deployed SHA is exactly that merge SHA, and verification observed exactly that SHA and passed.
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
