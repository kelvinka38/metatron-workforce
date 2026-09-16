package com.metatron.workforce.release;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
                return current;
            }
            throw new IllegalStateException("release-evidence-immutable:verification-already-passed:" + assignmentId);
        }
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
