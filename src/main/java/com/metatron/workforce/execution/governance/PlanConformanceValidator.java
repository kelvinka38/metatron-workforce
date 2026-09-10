package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.Objects;

/** Deterministic exact-plan checks reused by admission, execution and completion. */
public final class PlanConformanceValidator {
    public ExecutionPlanBinding.StepBinding requireWork(ExecutionPlanBinding plan, ExecutionWorkSpec work) {
        Objects.requireNonNull(plan); Objects.requireNonNull(work);
        if (plan.status() != ExecutionPlanBinding.Status.APPROVED) {
            throw new GovernanceDeniedException("PLAN_NOT_APPROVED", plan.planId() + "@" + plan.version());
        }
        ExecutionPlanBinding.StepBinding step = plan.steps().get(work.stepId());
        if (step == null) throw new GovernanceDeniedException("ACTION_OUTSIDE_PLAN", "unknown step:" + work.stepId());
        if (!step.workDigest().equals(WorkDigests.digest(work))) {
            throw new GovernanceDeniedException("PLAN_DEVIATION", "work digest changed for step:" + work.stepId());
        }
        if (step.consequence() != work.consequence()) {
            throw new GovernanceDeniedException("PLAN_DEVIATION", "work consequence changed for step:" + work.stepId());
        }
        return step;
    }

    public boolean actionAllowed(ExecutionPlanBinding plan, String stepId, String actionRef) {
        ExecutionPlanBinding.StepBinding step = plan.steps().get(stepId);
        return step != null && step.allowedActionRefs().contains(actionRef);
    }

    public boolean scopeAllowed(ExecutionPlanBinding plan, String stepId, String scope) {
        ExecutionPlanBinding.StepBinding step = plan.steps().get(stepId);
        if (step == null || scope == null || scope.isBlank()) return false;
        return step.allowedScopes().stream().anyMatch(allowed -> allowed.equals(scope)
                || allowed.endsWith("*") && scope.startsWith(allowed.substring(0, allowed.length() - 1)));
    }
}
