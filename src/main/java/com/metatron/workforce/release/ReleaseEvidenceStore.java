package com.metatron.workforce.release;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, monotonic, DURABLE store for {@link ReleaseEvidence}, one record per Assignment.
 * Backed by a {@link ReleaseEvidenceStateStore}: every mutator persists immediately after updating the
 * in-memory view, mirroring com.metatron.workforce.core.WorkforceCoreService's own persist()-after-every-
 * mutation pattern. On construction the store reloads from the configured ReleaseEvidenceStateStore, so
 * a process restart reproduces exactly the same evidence and cannot make a PRODUCTION_REQUIRED
 * Assignment permanently unverifiable (the chain is exactly where it was) nor accidentally completable
 * (nothing here ever marks anything COMPLETED -- that stays solely in WorkforceCoreService).
 *
 * Every mutator is idempotent for identical resubmission and rejects any attempt to change a field a
 * previous governed step already set to a different value. Later steps can only be recorded once their
 * prerequisite field already exists, and deployment is bound bit-for-bit to the previously recorded
 * merge SHA -- a caller cannot supply an unrelated "deployedSha" and have it accepted.
 *
 * Verification retry semantics: PASSED is terminal (immutable once reached). FAILED is NOT terminal --
 * a later verification attempt is allowed to retry (e.g. after production converges), but every attempt,
 * failed or passed, is appended to ReleaseEvidence.verificationHistory, so a later PASSED never silently
 * erases the audit fact that an earlier attempt failed.
 */
public class ReleaseEvidenceStore {
    private final Map<String, ReleaseEvidence> byAssignment;
    private final ReleaseEvidenceStateStore stateStore;
    private final Clock clock;

    public ReleaseEvidenceStore() { this(new InMemoryReleaseEvidenceStateStore(), Clock.systemUTC()); }
    public ReleaseEvidenceStore(Clock clock) { this(new InMemoryReleaseEvidenceStateStore(), clock); }
    public ReleaseEvidenceStore(ReleaseEvidenceStateStore stateStore) { this(stateStore, Clock.systemUTC()); }

    public ReleaseEvidenceStore(ReleaseEvidenceStateStore stateStore, Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore);
        this.clock = Objects.requireNonNull(clock);
        this.byAssignment = new ConcurrentHashMap<>(stateStore.load().byAssignment());
    }

    public synchronized ReleaseEvidence get(String assignmentId) { return byAssignment.get(assignmentId); }

    public synchronized ReleaseEvidence recordPrPublished(String assignmentId, String objectiveRef, String repository,
            int prNumber, String prHeadSha, String baseBranch, String baseSha, String producer) {
        require(assignmentId, "assignmentId"); require(repository, "repository");
        require(prHeadSha, "prHeadSha"); require(producer, "producer");
        ReleaseEvidence current = byAssignment.getOrDefault(assignmentId, ReleaseEvidence.empty(assignmentId, objectiveRef));
        if (current.repository() != null) {
            if (!repository.equals(current.repository()) || !Integer.valueOf(prNumber).equals(current.prNumber())
                    || !prHeadSha.equals(current.prHeadSha())) {
                throw new IllegalStateException("release-evidence-immutable:pr-already-recorded:" + assignmentId);
            }
            return current;
        }
        ReleaseEvidence next = new ReleaseEvidence(assignmentId, current.objectiveRef(), repository, prNumber, prHeadSha,
                baseBranch, baseSha, null, null, null, null, null, ReleaseEvidence.VerificationStatus.NONE,
                clock.instant(), null, null, null, producer, null, null, null, List.of());
        return persist(assignmentId, next);
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
                current.verificationRecordedAt(), current.prProducer(), producer, current.deployProducer(),
                current.verificationProducer(), current.verificationHistory());
        return persist(assignmentId, next);
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
                current.verificationRecordedAt(), current.prProducer(), current.mergeProducer(), producer,
                current.verificationProducer(), current.verificationHistory());
        return persist(assignmentId, next);
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
        if (current.verificationStatus() == ReleaseEvidence.VerificationStatus.PASSED) {
            if (expectedSha.equals(current.expectedProductionSha()) && observedSha.equals(current.observedProductionSha())
                    && reportedStatus == ReleaseEvidence.VerificationStatus.PASSED) {
                return current; // identical resubmission of an already-passed verification is idempotent
            }
            throw new IllegalStateException("release-evidence-immutable:verification-already-passed:" + assignmentId);
        }
        // The store itself, not the caller, decides PASSED vs FAILED: an observed/expected mismatch
        // always fails verification even if the caller reported PASSED.
        ReleaseEvidence.VerificationStatus effective = observedSha.equals(expectedSha)
                ? reportedStatus : ReleaseEvidence.VerificationStatus.FAILED;
        Instant now = clock.instant();
        List<String> history = new ArrayList<>(current.verificationHistory());
        history.add(effective + ":expected=" + expectedSha + ":observed=" + observedSha
                + ":producer=" + producer + ":at=" + now);
        ReleaseEvidence next = new ReleaseEvidence(current.assignmentId(), current.objectiveRef(), current.repository(),
                current.prNumber(), current.prHeadSha(), current.baseBranch(), current.baseSha(), current.mergeSha(),
                current.deployedSha(), current.imageDigest(), expectedSha, observedSha, effective,
                current.prRecordedAt(), current.mergeRecordedAt(), current.deployRecordedAt(), now,
                current.prProducer(), current.mergeProducer(), current.deployProducer(), producer, List.copyOf(history));
        return persist(assignmentId, next);
    }

    private ReleaseEvidence persist(String assignmentId, ReleaseEvidence next) {
        byAssignment.put(assignmentId, next);
        stateStore.save(new ReleaseEvidenceStateStore.Snapshot(Map.copyOf(byAssignment)));
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
