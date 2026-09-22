package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

final class AutonomousRecoveryPolicy {
    private AutonomousRecoveryPolicy() {}

    /**
     * Typed classification of a step failure string. Production incident (2026-09-22): the
     * bounded-local-retry policy treated every non-authorization/data/safety-gate/staffing failure
     * as equally retry-worthy, so a deterministically oversized cognition request (context-budget-
     * exceeded) and the in-runtime repeated-failure circuit breaker were both blindly retried up to
     * MAX_MUTATING_DISPATCH_ATTEMPTS times against an identical request that could never succeed,
     * wasting the whole bounded-retry budget before escalating for the same Human review it should
     * have reached immediately. Only {@link FailureClassification#TRANSIENT} is retry-eligible now.
     */
    static FailureClassification classify(String failure) {
        String value = failure == null ? "" : failure;
        if (value.startsWith("authorization-failure:") || value.contains("autonomy-safety-gate:")) {
            return FailureClassification.AUTHORIZATION_GOVERNANCE;
        }
        if (value.contains("staffing-gap:") || value.contains("capacity-unavailable:")) {
            return FailureClassification.HUMAN_REQUIRED;
        }
        // "data-failure:" is ActionContractCatalog rejecting an action's own declared input contract
        // (unexpected/missing/blank/mistyped input) -- a defect in the request itself, not external
        // conditions, so retrying the identical request can never succeed.
        if (value.startsWith("data-failure:")
                // The cognition context-prompt budget is exceeded by this exact request; nothing about
                // a bare retry changes its size.
                || value.contains("worker-cognition-request-context-budget-exceeded")
                // No supported dependency/build system was detected at the resolved project root;
                // retrying without a workspace change (e.g. workspace.project.prepare) cannot detect one.
                || value.contains("system not detected")
                // CognitiveWorkerRuntime's own repeated-failure circuit breaker already determined the
                // same action failed identically twice with no intervening state change -- see its
                // "bounded retry exhausted, a repair or Human diagnosis is required" reflection text.
                || value.contains("bounded retry exhausted, a repair or Human diagnosis is required")) {
            return FailureClassification.DETERMINISTIC_CONTRACT;
        }
        return FailureClassification.TRANSIENT;
    }

    /**
     * True only for a failure that genuinely requires a Human/operator: authorization/governance
     * denial, a deterministic contract defect no retry can fix, or a staffing/capacity gap. None of
     * these are ever silently retried, retried locally, or replanned -- they must stay BLOCKED
     * pending explicit human recovery.
     */
    private static boolean requiresHumanIntervention(String failure) {
        return classify(failure) != FailureClassification.TRANSIENT;
    }

    /**
     * REPLAN requires an explicit, trustworthy plan-invalid signal -- e.g. a durable plan that is
     * structurally impossible, not merely a step that failed to execute. No such automatic
     * classifier exists in this runtime today, so automatic REPLAN never fires for an ordinary
     * execution/provider/network/timeout/runtime failure, on ANY step, READ_ONLY or MUTATING:
     * replanning remains an explicit/manual operator action
     * (ManagementAutonomyService.requestReplan()), never an automatic runner response to a step
     * that merely failed to execute. READ_ONLY recovers via {@link #readOnlyRecoveryEligible} and
     * MUTATING via {@link #boundedLocalRetryEligible}; exhausting either bound escalates for Human
     * review instead of falling back to REPLAN.
     */
    static boolean autonomousReplanEligible(ExecutionWorkSpec step, String failure) {
        return false;
    }

    /**
     * True for an ordinary (non-human-required) READ_ONLY execution/provider/network/timeout
     * failure that bounded read-only retry may resolve. Exhausting that bound never falls back to
     * REPLAN -- see AutonomousManagementRunner.handleReadOnlyRecoveryExhausted().
     */
    static boolean readOnlyRecoveryEligible(ExecutionWorkSpec step, String failure) {
        if (requiresHumanIntervention(failure)) return false;
        return step.consequence() == ExecutionWorkSpec.Consequence.READ_ONLY;
    }

    /**
     * Ordinary MUTATING execution failures on the general-workspace pipeline (PRODUCE/PREPARE/
     * VERIFY/DELIVER) recover via a bounded retry of the SAME failed step, in the SAME graph
     * version -- never a replan, so completed phases are never re-planned or re-dispatched and
     * only the failed phase is retried.
     */
    static boolean boundedLocalRetryEligible(ExecutionWorkSpec step, String failure) {
        if (requiresHumanIntervention(failure)) return false;
        return step.consequence() == ExecutionWorkSpec.Consequence.MUTATING
                && GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(step.requiredCapability());
    }
}
