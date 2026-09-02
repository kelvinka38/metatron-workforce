package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

final class AutonomousRecoveryPolicy {
    private AutonomousRecoveryPolicy() {}

    static boolean autonomousReplanEligible(ExecutionWorkSpec step, String failure) {
        String value = failure == null ? "" : failure;
        if (value.startsWith("authorization-failure:")
                || value.startsWith("data-failure:")
                || value.startsWith("autonomy-safety-gate:")
                || value.contains("staffing-gap:")
                || value.contains("capacity-unavailable:")) {
            return false;
        }
        return step.consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                || GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(step.requiredCapability());
    }
}
