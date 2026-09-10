package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Converts an already-authorized bounded WorkSpec into a versioned plan binding.
 * An existing binding is never silently rewritten: changed Work becomes PLAN_DEVIATION.
 */
public final class GovernancePlanService {
    public record BoundPlan(SotDiscoveryRecord discovery, AuthoritySnapshot snapshot,
                            ConstraintBundle constraints, DerivationReceipt derivation,
                            ExecutionPlanBinding plan) {}

    private final SotDiscoveryService discovery;
    private final DerivationValidator derivation;
    private final GovernanceStateStore store;
    private final Clock clock;

    public GovernancePlanService(SotDiscoveryService discovery, DerivationValidator derivation,
                                 GovernanceStateStore store, Clock clock) {
        this.discovery = Objects.requireNonNull(discovery); this.derivation = Objects.requireNonNull(derivation);
        this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
    }

    public BoundPlan bindAuthorizedWork(String objectiveId, String actorId, ExecutionWorkSpec work,
                                        String approvedBy, String approvalReference,
                                        Map<String, String> reviewEvidence) {
        Objects.requireNonNull(work, "work");
        SotDiscoveryService.Result found = discovery.discover(objectiveId, actorId, "EXECUTION", work.target());

        ExecutionPlanBinding existing = store.approvedPlanForStep(objectiveId, work.stepId()).orElse(null);
        if (existing != null) {
            if (!existing.authorityDigest().equals(found.snapshot().digest())) {
                throw new GovernanceDeniedException("AUTHORITY_STALE",
                        "approved plan bound to prior authority:" + existing.planId() + "@" + existing.version());
            }
            ExecutionPlanBinding.StepBinding step = existing.steps().get(work.stepId());
            if (step == null || !step.workDigest().equals(WorkDigests.digest(work))) {
                throw new GovernanceDeniedException("PLAN_DEVIATION",
                        "approved plan cannot be silently replaced for step:" + work.stepId());
            }
            DerivationReceipt receipt = store.derivationReceipt(existing.derivationReceiptId())
                    .orElseThrow(() -> new GovernanceDeniedException("DERIVATION_UNVERIFIED", "approved plan receipt missing"));
            return new BoundPlan(found.discovery(), found.snapshot(), found.constraints(), receipt, existing);
        }

        DerivationReceipt receipt = derivation.derive(objectiveId, work, found.snapshot(), found.constraints(), false);
        if (receipt.classification() == DerivationReceipt.Classification.CHANGE_PROPOSAL_REQUIRED) {
            throw new GovernanceDeniedException("CHANGE_PROPOSAL_REQUIRED", "derivation found authority conflict/contradiction");
        }
        if (!receipt.executableCandidate()) {
            throw new GovernanceDeniedException("DERIVATION_UNVERIFIED", receipt.classification().name());
        }
        Map<String, String> reviews = reviewEvidence == null ? Map.of() : Map.copyOf(reviewEvidence);
        for (ConstraintBinding constraint : found.constraints().constraints()) {
            if (constraint.kind() == ConstraintBinding.Kind.REVIEW
                    && (reviews.get(constraint.constraintId()) == null || reviews.get(constraint.constraintId()).isBlank())) {
                throw new GovernanceDeniedException("REVIEW_REQUIRED", constraint.constraintId());
            }
        }
        ExecutionPlanBinding plan = ExecutionPlanBinding.approvedSingleStep(
                "plan:" + objectiveId + ":" + work.stepId(), 1, receipt, found.snapshot(), work,
                PlanEffectPolicy.allowedActions(work), PlanEffectPolicy.allowedScopes(work, found.snapshot()), reviews,
                require(approvedBy, "approvedBy"), require(approvalReference, "approvalReference"), clock.instant());
        store.savePlanBinding(plan);
        return new BoundPlan(found.discovery(), found.snapshot(), found.constraints(), receipt, plan);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new GovernanceDeniedException("PLAN_NOT_APPROVED", field + " missing");
        return value.trim();
    }
}
