package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

final class AutonomousRecoveryPolicy {
    private AutonomousRecoveryPolicy() {}

    /**
     * True only for a failure that genuinely requires a Human/operator: authorization denial, data
     * validity, an explicit safety-gate denial, or a staffing/capacity gap. None of these are ever
     * silently retried, retried locally, or replanned -- they must stay BLOCKED pending explicit
     * human recovery.
     */
    private static boolean requiresHumanIntervention(String failure) {
        String value = failure == null ? "" : failure;
        return value.startsWith("authorization-failure:")
                || value.startsWith("data-failure:")
                || value.startsWith("autonomy-safety-gate:")
                || value.contains("staffing-gap:")
                || value.contains("capacity-unavailable:");
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
