package com.metatron.workforce.release;

import java.time.Instant;
import java.util.List;

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
