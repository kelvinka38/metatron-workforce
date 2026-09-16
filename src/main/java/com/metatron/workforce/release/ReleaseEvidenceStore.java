package com.metatron.workforce.release;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, monotonic store for {@link ReleaseEvidence}, one record per Assignment. Every mutator is
 * idempotent for identical resubmission and rejects any attempt to change a field a previous governed
 * step already set to a different value. Later steps can only be recorded once their prerequisite field
 * already exists, and deployment is bound bit-for-bit to the previously recorded merge SHA -- a caller
 * cannot supply an unrelated "deployedSha" and have it accepted.
 */
public class ReleaseEvidenceStore {
    private final Map<String, ReleaseEvidence> byAssignment = new ConcurrentHashMap<>();
    private final Clock clock;

    public ReleaseEvidenceStore() { this(Clock.systemUTC()); }
    public ReleaseEvidenceStore(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public synchronized ReleaseEvidence get(String assignmentId) { return byAssignment.get(assignmentId); }

    public synchronized ReleaseEvidence recordPrPublished(String assignmentId, String objectiveRef, String repository,
            int prNumber, String prHeadSha, String baseBranch, String baseSha, String producer) {
        require(assignmentId, "assignmentId"); require(repository, "repository");
        require(prHeadSha, "prHeadSha"); require(producer, "producer");
        ReleaseEvidence current = byAssignment.computeIfAbsent(assignmentId, id -> ReleaseEvidence.empty(id, objectiveRef));
        if (current.repository() != null) {
            if (!repository.equals(current.repository()) || !Integer.valueOf(prNumber).equals(current.prNumber())
                    || !prHeadSha.equals(current.prHeadSha())) {
                throw new IllegalStateException("release-evidence-immutable:pr-already-recorded:" + assignmentId);
            }
            return current;
        }
        ReleaseEvidence next = new ReleaseEvidence(assignmentId, current.objectiveRef(), repository, prNumber, prHeadSha,
                baseBranch, baseSha, null, null, null, null, null, ReleaseEvidence.VerificationStatus.NONE,
                clock.instant(), null, null, null, producer, null, null, null);
        byAssignment.put(assignmentId, next);
        return next;
    }

    public synchronized ReleaseEvidence recordMerge(String assignmentId, String mergeSha, String producer) {
        require(assignmentId, "assignmentId"); require(mergeSha, "mergeSha"); require(producer, "producer");
        ReleaseEvidence current = requireExisting(assignmentId);
        if (!current.satisfiesPrRequired())
            throw new IllegalStateException("release-evidence-sequence-violation:merge-requires-pr-evidence:" + assignmentId);
        if (current.mergeSha() != null) {
            if (!current.mergeSha().equals(mergeSha))
                throw new IllegalStateException("release-evidence-immutable:merge-sha-already-recorded:" + assignmentId);
            return current;
        }
        ReleaseEvidence next = new ReleaseEvidence(current.assignmentId(), current.objectiveRef(), current.repository(),
                current.prNumber(), current.prHeadSha(), current.baseBranch(), current.baseSha(), mergeSha,
                current.deployedSha(), current.imageDigest(), current.expectedProductionSha(), current.observedProductionSha(),
                current.verificationStatus(), current.prRecordedAt(), clock.instant(), current.deployRecordedAt(),
                current.verificationRecordedAt(), current.prProducer(), producer, current.deployProducer(), current.verificationProducer());
        byAssignment.put(assignmentId, next);
        return next;
    }

    public synchronized ReleaseEvidence recordDeployment(String assignmentId, String deployedSha, String imageDigest, String producer) {
        require(assignmentId, "assignmentId"); require(deployedSha, "deployedSha"); require(producer, "producer");
        ReleaseEvidence current = requireExisting(assignmentId);
        if (current.mergeSha() == null)
            throw new IllegalStateException("release-evidence-sequence-violation:deploy-requires-merge-evidence:" + assignmentId);
        if (!current.mergeSha().equals(deployedSha))
            throw new IllegalStateException("release-evidence-sha-mismatch:deployed-sha-not-derived-from-merge-sha:" + assignmentId);
        if (current.deployedSha() != null) {
            if (!current.deployedSha().equals(deployedSha))
                throw new IllegalStateException("release-evidence-immutable:deployed-sha-already-recorded:" + assignmentId);
            return current;
        }
        ReleaseEvidence next = new ReleaseEvidence(current.assignmentId(), current.objectiveRef(), current.repository(),
                current.prNumber(), current.prHeadSha(), current.baseBranch(), current.baseSha(), current.mergeSha(),
                deployedSha, imageDigest, current.expectedProductionSha(), current.observedProductionSha(),
                current.verificationStatus(), current.prRecordedAt(), current.mergeRecordedAt(), clock.instant(),
                current.verificationRecordedAt(), current.prProducer(), current.mergeProducer(), producer, current.verificationProducer());
        byAssignment.put(assignmentId, next);
        return next;
    }

    public synchronized ReleaseEvidence recordVerification(String assignmentId, String expectedSha, String observedSha,
            ReleaseEvidence.VerificationStatus reportedStatus, String producer) {
        require(assignmentId, "assignmentId"); require(expectedSha, "expectedSha");
        require(observedSha, "observedSha"); Objects.requireNonNull(reportedStatus, "reportedStatus"); require(producer, "producer");
        ReleaseEvidence current = requireExisting(assignmentId);
        if (current.deployedSha() == null)
            throw new IllegalStateException("release-evidence-sequence-violation:verification-requires-deploy-evidence:" + assignmentId);
        if (!current.deployedSha().equals(expectedSha))
            throw new IllegalStateException("release-evidence-sha-mismatch:expected-sha-not-deployed-sha:" + assignmentId);
        // The store itself, not the caller, decides PASSED vs FAILED: a mismatch between observed and
        // expected always fails verification even if the caller reported PASSED.
        ReleaseEvidence.VerificationStatus effective = observedSha.equals(expectedSha)
                ? reportedStatus : ReleaseEvidence.VerificationStatus.FAILED;
        if (current.verificationStatus() == ReleaseEvidence.VerificationStatus.PASSED) {
            if (effective == ReleaseEvidence.VerificationStatus.PASSED
                    && expectedSha.equals(current.expectedProductionSha())
                    && observedSha.equals(current.observedProductionSha())) {
                return current;
            }
            throw new IllegalStateException("release-evidence-immutable:verification-already-passed:" + assignmentId);
        }
        ReleaseEvidence next = new ReleaseEvidence(current.assignmentId(), current.objectiveRef(), current.repository(),
                current.prNumber(), current.prHeadSha(), current.baseBranch(), current.baseSha(), current.mergeSha(),
                current.deployedSha(), current.imageDigest(), expectedSha, observedSha, effective,
                current.prRecordedAt(), current.mergeRecordedAt(), current.deployRecordedAt(), clock.instant(),
                current.prProducer(), current.mergeProducer(), current.deployProducer(), producer);
        byAssignment.put(assignmentId, next);
        return next;
    }

    private ReleaseEvidence requireExisting(String assignmentId) {
        return Optional.ofNullable(byAssignment.get(assignmentId))
                .orElseThrow(() -> new IllegalStateException("release-evidence-not-found:" + assignmentId));
    }

    private static void require(Object value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " required");
    }
}
