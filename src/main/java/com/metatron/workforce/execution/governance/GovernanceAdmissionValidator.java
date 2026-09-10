package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.Objects;

/** Validates exact authority/derivation/plan identities before consequential execution is admitted. */
public final class GovernanceAdmissionValidator {
    private final GovernanceStateStore store;
    private final AuthorityFreshnessValidator freshness;
    private final PlanConformanceValidator planConformance;

    public GovernanceAdmissionValidator(GovernanceStateStore store, AuthorityFreshnessValidator freshness,
                                        PlanConformanceValidator planConformance) {
        this.store = Objects.requireNonNull(store); this.freshness = Objects.requireNonNull(freshness);
        this.planConformance = Objects.requireNonNull(planConformance);
    }

    public ExecutionPlanBinding validate(ExecutionWorkSpec work,
                                         String authoritySnapshotId, String derivationReceiptId,
                                         String planId, int planVersion) {
        Objects.requireNonNull(work, "work");
        if (planVersion < 1) throw new GovernanceDeniedException("PLAN_NOT_APPROVED", "plan version missing");
        ExecutionPlanBinding plan = store.planBinding(require(planId, "PLAN_NOT_APPROVED"), planVersion)
                .orElseThrow(() -> new GovernanceDeniedException("PLAN_NOT_APPROVED", "plan binding not found"));
        String objectiveId = plan.objectiveId();

        AuthoritySnapshot snapshot = store.snapshot(require(authoritySnapshotId, "SOT_DISCOVERY_REQUIRED"))
                .orElseThrow(() -> new GovernanceDeniedException("SOT_DISCOVERY_REQUIRED", "authority snapshot not found"));
        freshness.requireCurrent(snapshot);
        if (!objectiveId.equals(snapshot.objectiveId())) throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "objective/snapshot mismatch");

        DerivationReceipt receipt = store.derivationReceipt(require(derivationReceiptId, "DERIVATION_UNVERIFIED"))
                .orElseThrow(() -> new GovernanceDeniedException("DERIVATION_UNVERIFIED", "receipt not found"));
        if (!receipt.executableCandidate() || !receipt.objectiveId().equals(objectiveId)
                || !receipt.authoritySnapshotId().equals(snapshot.snapshotId())
                || !receipt.authorityDigest().equals(snapshot.digest()) || !receipt.workDigest().equals(WorkDigests.digest(work))) {
            throw new GovernanceDeniedException("DERIVATION_UNVERIFIED", "receipt does not match current work/authority");
        }

        if (!plan.authoritySnapshotId().equals(snapshot.snapshotId()) || !plan.authorityDigest().equals(snapshot.digest())
                || !plan.derivationReceiptId().equals(receipt.receiptId())) {
            throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "plan authority/receipt mismatch");
        }
        planConformance.requireWork(plan, work);
        ExecutionPlanBinding active = store.approvedPlanForStep(objectiveId, work.stepId())
                .orElseThrow(() -> new GovernanceDeniedException("PLAN_NOT_APPROVED", "no active approved plan for step"));
        if (!active.planId().equals(plan.planId()) || active.version() != plan.version() || !active.planDigest().equals(plan.planDigest())) {
            throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "supplied plan is not the active plan");
        }
        return plan;
    }

    private static String require(String value, String code) {
        if (value == null || value.isBlank()) throw new GovernanceDeniedException(code, "required governance reference missing");
        return value.trim();
    }
}
