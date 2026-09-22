package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousRecoveryPolicyTest {
    private static ExecutionWorkSpec step(String capability, ExecutionWorkSpec.Consequence consequence) {
        return new ExecutionWorkSpec("step", "objective", "target", capability, List.of(), consequence,
                List.of("accepted"), List.of("evidence"));
    }

    @Test void ordinaryMutatingFailureIsClassifiedForCapabilityOwnedRecoveryNeverReplan() {
        ExecutionWorkSpec general = step(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                ExecutionWorkSpec.Consequence.MUTATING);
        ExecutionWorkSpec pr = step(RepositoryPullRequestAutonomousCapability.CAPABILITY,
                ExecutionWorkSpec.Consequence.MUTATING);

        assertTrue(AutonomousRecoveryPolicy.mutatingExecutionFailureEligible(
                general, "capability-unsuccessful:cognitive worker failed"));
        assertTrue(AutonomousRecoveryPolicy.mutatingExecutionFailureEligible(
                pr, "runtime-failure:temporary"));
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(
                general, "capability-unsuccessful:cognitive worker failed"));
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(
                pr, "runtime-failure:temporary"));
    }

    @Test void readOnlyProviderOrRuntimeFailureIsBoundedRetryEligibleNeverReplan() {
        ExecutionWorkSpec step = step("test.recovery.read", ExecutionWorkSpec.Consequence.READ_ONLY);
        assertTrue(AutonomousRecoveryPolicy.readOnlyRecoveryEligible(step, "provider-timeout"));
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(step, "provider-timeout"));
        assertFalse(AutonomousRecoveryPolicy.mutatingExecutionFailureEligible(step, "provider-timeout"));
    }

    @Test void humanAuthorizationAndSafetyFailuresStayFailClosed() {
        ExecutionWorkSpec mutation = step(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                ExecutionWorkSpec.Consequence.MUTATING);
        assertFalse(AutonomousRecoveryPolicy.mutatingExecutionFailureEligible(
                mutation, "autonomy-safety-gate:denied"));
        assertFalse(AutonomousRecoveryPolicy.mutatingExecutionFailureEligible(
                mutation, "authorization-failure:denied"));
        assertFalse(AutonomousRecoveryPolicy.mutatingExecutionFailureEligible(
                mutation, "data-failure:invalid"));

        ExecutionWorkSpec readOnly = step(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                ExecutionWorkSpec.Consequence.READ_ONLY);
        assertFalse(AutonomousRecoveryPolicy.readOnlyRecoveryEligible(
                readOnly, "authorization-failure:denied"));
    }
}
