package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned execution contract; approved plan identity is immutable and authority-bound. */
public record ExecutionPlanBinding(
        String planId,
        int version,
        String planDigest,
        String derivationReceiptId,
        String authoritySnapshotId,
        String authorityDigest,
        String constraintBundleId,
        String objectiveId,
        String targetScope,
        Map<String, StepBinding> steps,
        List<String> acceptanceCriteria,
        List<String> evidenceRequirements,
        Map<String, String> reviewEvidence,
        Status status,
        String approvedBy,
        String approvalReference,
        Instant approvedAt) {

    public enum Status { DRAFT, APPROVED, SUPERSEDED, REVOKED }

    public record StepBinding(String stepId, String workDigest, ExecutionWorkSpec.Consequence consequence,
                              Set<String> allowedActionRefs, Set<String> allowedScopes) {
        public StepBinding {
            stepId = require(stepId, "stepId");
            workDigest = require(workDigest, "workDigest");
            Objects.requireNonNull(consequence, "consequence");
            allowedActionRefs = allowedActionRefs == null ? Set.of() : Set.copyOf(allowedActionRefs);
            allowedScopes = allowedScopes == null ? Set.of() : Set.copyOf(allowedScopes);
        }
    }

    public ExecutionPlanBinding {
        planId = require(planId, "planId");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        planDigest = require(planDigest, "planDigest");
        derivationReceiptId = require(derivationReceiptId, "derivationReceiptId");
        authoritySnapshotId = require(authoritySnapshotId, "authoritySnapshotId");
        authorityDigest = require(authorityDigest, "authorityDigest");
        constraintBundleId = require(constraintBundleId, "constraintBundleId");
        objectiveId = require(objectiveId, "objectiveId");
        targetScope = require(targetScope, "targetScope");
        steps = steps == null ? Map.of() : Map.copyOf(steps);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        evidenceRequirements = evidenceRequirements == null ? List.of() : List.copyOf(evidenceRequirements);
        reviewEvidence = reviewEvidence == null ? Map.of() : Map.copyOf(reviewEvidence);
        Objects.requireNonNull(status, "status");
        approvedBy = approvedBy == null ? "" : approvedBy.trim();
        approvalReference = approvalReference == null ? "" : approvalReference.trim();
        if (steps.isEmpty()) throw new IllegalArgumentException("plan steps required");
        if (status == Status.APPROVED) {
            if (approvedBy.isBlank() || approvalReference.isBlank() || approvedAt == null) {
                throw new IllegalArgumentException("approved plan requires approver/reference/time");
            }
        }
    }

    public static ExecutionPlanBinding approvedSingleStep(String planId, int version,
                                                           DerivationReceipt receipt,
                                                           AuthoritySnapshot snapshot,
                                                           ExecutionWorkSpec work,
                                                           Set<String> allowedActionRefs,
                                                           Set<String> allowedScopes,
                                                           Map<String, String> reviewEvidence,
                                                           String approvedBy,
                                                           String approvalReference,
                                                           Instant approvedAt) {
        Objects.requireNonNull(receipt); Objects.requireNonNull(snapshot); Objects.requireNonNull(work);
        if (!receipt.executableCandidate()) throw new GovernanceDeniedException("DERIVATION_UNVERIFIED", receipt.classification().name());
        String workDigest = WorkDigests.digest(work);
        if (!workDigest.equals(receipt.workDigest()) || !snapshot.digest().equals(receipt.authorityDigest())) {
            throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "receipt/work/authority mismatch");
        }
        StepBinding step = new StepBinding(work.stepId(), workDigest, work.consequence(), allowedActionRefs, allowedScopes);
        String material = planId + "|" + version + "|" + receipt.receiptId() + "|" + snapshot.digest() + "|"
                + work.stepId() + "|" + workDigest + "|" + work.consequence() + "|"
                + sorted(step.allowedActionRefs()) + "|" + sorted(step.allowedScopes());
        String digest = GovernanceDigests.sha256(material);
        return new ExecutionPlanBinding(planId, version, digest, receipt.receiptId(), snapshot.snapshotId(),
                snapshot.digest(), receipt.constraintBundleId(), receipt.objectiveId(), snapshot.targetScope(),
                Map.of(work.stepId(), step), work.acceptanceCriteria(), work.evidenceRequirements(), reviewEvidence,
                Status.APPROVED, approvedBy, approvalReference, approvedAt);
    }

    private static String sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).reduce("", (a, b) -> a + b + "\u001f");
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
