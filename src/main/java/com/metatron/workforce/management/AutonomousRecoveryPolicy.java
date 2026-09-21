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
     * REPLAN is reserved for a genuinely invalid/impossible plan -- today, an exhausted bounded
     * READ_ONLY retry, where the durable plan itself may need reconsideration. It must never fire
     * for an ordinary MUTATING execution failure (a command/provider/git/timeout/runtime error):
     * those recover via {@link #boundedLocalRetryEligible}, never by asking the planner again.
     */
    static boolean autonomousReplanEligible(ExecutionWorkSpec step, String failure) {
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
