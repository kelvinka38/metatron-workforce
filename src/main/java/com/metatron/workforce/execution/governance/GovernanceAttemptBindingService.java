package com.metatron.workforce.execution.governance;

import com.metatron.workforce.execution.ExecutionAttempt;

import java.time.Clock;
import java.util.Objects;

/** Attaches exact governance identity to the existing Execution-owned lease/fencing attempt. */
public final class GovernanceAttemptBindingService {
    private final GovernanceStateStore store;
    private final Clock clock;

    public GovernanceAttemptBindingService(GovernanceStateStore store, Clock clock) {
        this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
    }

    public ExecutionAttemptGovernanceBinding bind(ExecutionAttempt attempt, GovernancePlanService.BoundPlan bound) {
        Objects.requireNonNull(attempt); Objects.requireNonNull(bound);
        ExecutionPlanBinding plan = bound.plan();
        if (!attempt.objectiveId().equals(plan.objectiveId()) || !attempt.stepId().equals(bound.derivation().stepId())) {
            throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "attempt/objective/step mismatch");
        }
        ExecutionAttemptGovernanceBinding existing = store.attemptBinding(attempt.attemptId()).orElse(null);
        if (existing != null) {
            if (existing.fencingToken() != attempt.fencingToken() || !existing.planDigest().equals(plan.planDigest())
                    || !existing.authorityDigest().equals(bound.snapshot().digest())) {
                throw new GovernanceDeniedException("PLAN_BINDING_MISMATCH", "attempt already bound to different governance identity");
            }
            return existing;
        }
        ExecutionAttemptGovernanceBinding binding = new ExecutionAttemptGovernanceBinding(
                attempt.attemptId(), attempt.fencingToken(), attempt.objectiveId(), attempt.stepId(),
                bound.snapshot().snapshotId(), bound.snapshot().digest(), bound.derivation().receiptId(),
                plan.planId(), plan.version(), plan.planDigest(), clock.instant());
        store.saveAttemptBinding(binding);
        return binding;
    }
}
