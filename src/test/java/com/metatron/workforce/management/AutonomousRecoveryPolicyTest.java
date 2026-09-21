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

    @Test void generalWorkspaceMutatingFailureIsBoundedLocalRetryEligibleNeverReplan() {
        ExecutionWorkSpec step = step(GeneralWorkspaceAutonomousCapability.CAPABILITY, ExecutionWorkSpec.Consequence.MUTATING);
        assertTrue(AutonomousRecoveryPolicy.boundedLocalRetryEligible(
                step, "capability-unsuccessful:cognitive worker failed"),
                "an ordinary DELIVER/PRODUCE/PREPARE/VERIFY execution failure must recover via bounded "
                        + "step-local retry");
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(
                step, "capability-unsuccessful:cognitive worker failed"),
                "REPLAN must never be used as recovery for an ordinary general-workspace execution failure");
    }

    @Test void readOnlyProviderOrRuntimeFailureIsBoundedRetryEligibleNeverReplan() {
        ExecutionWorkSpec step = step("test.recovery.read", ExecutionWorkSpec.Consequence.READ_ONLY);
        assertTrue(AutonomousRecoveryPolicy.readOnlyRecoveryEligible(step, "provider-timeout"),
                "an ordinary READ_ONLY provider/runtime failure must recover via bounded read-only retry");
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(step, "provider-timeout"),
                "REPLAN must never be used as recovery for an exhausted ordinary READ_ONLY retry -- no "
                        + "trustworthy automatic plan-invalid signal exists");
        assertFalse(AutonomousRecoveryPolicy.boundedLocalRetryEligible(step, "provider-timeout"),
                "bounded step-local retry (PR #489) only ever applies to the MUTATING general-workspace pipeline");
    }

    @Test void unrelatedMutationAndSafetyFailuresStayFailClosed() {
        ExecutionWorkSpec unrelated = step("repository.pr.propose", ExecutionWorkSpec.Consequence.MUTATING);
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(unrelated, "runtime-failure:x"));
        assertFalse(AutonomousRecoveryPolicy.boundedLocalRetryEligible(unrelated, "runtime-failure:x"));
        assertFalse(AutonomousRecoveryPolicy.readOnlyRecoveryEligible(unrelated, "runtime-failure:x"),
                "bounded read-only retry never applies to a MUTATING step");

        ExecutionWorkSpec generalWorkspace = step(GeneralWorkspaceAutonomousCapability.CAPABILITY, ExecutionWorkSpec.Consequence.MUTATING);
        assertFalse(AutonomousRecoveryPolicy.autonomousReplanEligible(generalWorkspace, "autonomy-safety-gate:denied"));
        assertFalse(AutonomousRecoveryPolicy.boundedLocalRetryEligible(generalWorkspace, "autonomy-safety-gate:denied"),
                "a genuine safety-gate denial must never be bounded-locally-retried either -- only Human recovery");

        ExecutionWorkSpec readOnlyGeneralWorkspace = step(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                ExecutionWorkSpec.Consequence.READ_ONLY);
        assertFalse(AutonomousRecoveryPolicy.readOnlyRecoveryEligible(readOnlyGeneralWorkspace, "authorization-failure:denied"),
                "a genuine authorization failure must never be bounded-read-only-retried either -- only Human recovery");
    }
}
