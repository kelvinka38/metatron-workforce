package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test void deterministicContractFailuresAreNeverBlindlyRetried() {
        // Production incident: a deterministically oversized cognition request (context-budget-exceeded)
        // and the in-runtime repeated-failure circuit breaker were both blindly bounded-locally-retried up
        // to MAX_MUTATING_DISPATCH_ATTEMPTS times against a request that could never succeed on an
        // unmodified retry, wasting the whole bounded-retry budget before finally escalating for the exact
        // same Human review it should have reached on the very first attempt.
        ExecutionWorkSpec generalWorkspace = step(GeneralWorkspaceAutonomousCapability.CAPABILITY, ExecutionWorkSpec.Consequence.MUTATING);
        ExecutionWorkSpec readOnlyGeneralWorkspace = step(GeneralWorkspaceAutonomousCapability.CAPABILITY, ExecutionWorkSpec.Consequence.READ_ONLY);

        assertEquals(FailureClassification.DETERMINISTIC_CONTRACT, AutonomousRecoveryPolicy.classify(
                "logic-or-provider-failure:worker-cognition-request-context-budget-exceeded:chars=8753:limit=7000"));
        assertFalse(AutonomousRecoveryPolicy.boundedLocalRetryEligible(generalWorkspace,
                "logic-or-provider-failure:worker-cognition-request-context-budget-exceeded:chars=8753:limit=7000"),
                "a deterministically oversized cognition request cannot shrink on a bare retry");

        assertEquals(FailureClassification.DETERMINISTIC_CONTRACT, AutonomousRecoveryPolicy.classify(
                "capability-unsuccessful:Deterministic action workspace.build.run failed identically twice with no "
                        + "intervening state change; bounded retry exhausted, a repair or Human diagnosis is "
                        + "required. Last failure detail: action threw IllegalStateException: workspace build "
                        + "system not detected"));
        assertFalse(AutonomousRecoveryPolicy.boundedLocalRetryEligible(generalWorkspace,
                "capability-unsuccessful:Deterministic action workspace.build.run failed identically twice with no "
                        + "intervening state change; bounded retry exhausted, a repair or Human diagnosis is "
                        + "required. Last failure detail: action threw IllegalStateException: workspace build "
                        + "system not detected"),
                "CognitiveWorkerRuntime's own repeated-failure circuit breaker has already determined this cannot "
                        + "succeed on an unmodified retry; the outer step-dispatch retry must not repeat it");

        assertEquals(FailureClassification.DETERMINISTIC_CONTRACT,
                AutonomousRecoveryPolicy.classify("data-failure:action-input-unexpected:action=workspace.file.write:key=bogus"));
        assertFalse(AutonomousRecoveryPolicy.readOnlyRecoveryEligible(readOnlyGeneralWorkspace,
                "data-failure:action-input-unexpected:action=workspace.file.write:key=bogus"),
                "an action's own declared input contract being violated cannot be fixed by an identical retry");

        // A genuinely transient failure (network/provider/timeout) is unaffected and still retry-eligible.
        assertEquals(FailureClassification.TRANSIENT, AutonomousRecoveryPolicy.classify("runtime-failure:connection reset"));
        assertTrue(AutonomousRecoveryPolicy.boundedLocalRetryEligible(generalWorkspace, "runtime-failure:connection reset"));
    }
}
