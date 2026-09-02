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

    @Test void generalWorkspaceMutatingFailureCanReplan() {
        assertTrue(AutonomousRecoveryPolicy.autonomousReplanEligible(
                step(GeneralWorkspaceAutonomousCapability.CAPABILITY, ExecutionWorkSpec.Consequence.MUTATING),
                "capability-unsuccessful:cognitive worker failed"));
    }

    @Test void unrelatedMutationAndSafetyFailuresStayFailClosed() {
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(
                step("repository.pr.propose", ExecutionWorkSpec.Consequence.MUTATING), "runtime-failure:x"));
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(
                step(GeneralWorkspaceAutonomousCapability.CAPABILITY, ExecutionWorkSpec.Consequence.MUTATING),
                "autonomy-safety-gate:denied"));
    }
}
