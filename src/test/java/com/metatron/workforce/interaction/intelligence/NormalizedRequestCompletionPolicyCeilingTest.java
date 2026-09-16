package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.core.CompletionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NormalizedRequestCompletionPolicyCeilingTest {

    @Test
    void plannerStepThatOmitsCompletionPolicyIsRaisedToTheObjectivesRequirement() {
        ExecutionWorkSpec plannerStep = defaultPlannerStep();
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, plannerStep.completionPolicy());

        NormalizedRequest request = baseExecutionRequest()
                .withCompletionPolicy(CompletionPolicy.PRODUCTION_REQUIRED)
                .withExecutionWorkPlan(List.of(plannerStep));

        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, request.executionWorkPlan().get(0).completionPolicy(),
                "the ceiling must have raised the planner's under-declared step, not left it at EXECUTION_REQUIRED");
    }

    @Test
    void plannerStepThatAlreadyDeclaresSomethingStrongerIsNeverLowered() {
        ExecutionWorkSpec plannerStep = new ExecutionWorkSpec("step-1", "objective text", "target", "cap",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING, List.of(), List.of(), CompletionPolicy.PRODUCTION_REQUIRED);

        NormalizedRequest request = baseExecutionRequest()
                .withCompletionPolicy(CompletionPolicy.PR_REQUIRED)
                .withExecutionWorkPlan(List.of(plannerStep));

        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, request.executionWorkPlan().get(0).completionPolicy(),
                "ceiling raises, never lowers -- a step already stronger than the Objective floor is untouched");
    }

    @Test
    void settingCompletionPolicyAfterThePlanIsAttachedStillAppliesTheCeiling() {
        NormalizedRequest request = baseExecutionRequest()
                .withExecutionWorkPlan(List.of(defaultPlannerStep()))
                .withCompletionPolicy(CompletionPolicy.PR_REQUIRED);

        assertEquals(CompletionPolicy.PR_REQUIRED, request.executionWorkPlan().get(0).completionPolicy());
    }

    @Test
    void ordinaryExecutionOnlyObjectiveDefaultsToExecutionRequiredUnchanged() {
        NormalizedRequest request = baseExecutionRequest().withExecutionWorkPlan(List.of(defaultPlannerStep()));
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, request.completionPolicy());
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, request.executionWorkPlan().get(0).completionPolicy());
    }

    @Test
    void withRequestedDepthDoesNotSilentlyResetTheCompletionPolicy() {
        NormalizedRequest strong = baseExecutionRequest()
                .withCompletionPolicy(CompletionPolicy.PRODUCTION_REQUIRED)
                .withExecutionWorkPlan(List.of(defaultPlannerStep()));

        NormalizedRequest afterUnrelatedChange = strong.withRequestedDepth(IntelligenceDepth.DEEP);

        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, afterUnrelatedChange.completionPolicy());
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, afterUnrelatedChange.executionWorkPlan().get(0).completionPolicy());
    }

    @Test
    void multiStepPlanEachStepIndependentlyRaisedToTheObjectiveFloor() {
        ExecutionWorkSpec stepA = defaultPlannerStep();
        ExecutionWorkSpec stepB = new ExecutionWorkSpec("step-2", "second step", "target", "cap",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING, List.of(), List.of(), CompletionPolicy.PR_REQUIRED);

        NormalizedRequest request = baseExecutionRequest()
                .withCompletionPolicy(CompletionPolicy.PRODUCTION_REQUIRED)
                .withExecutionWorkPlan(List.of(stepA, stepB));

        assertTrue(request.executionWorkPlan().stream()
                .allMatch(s -> s.completionPolicy() == CompletionPolicy.PRODUCTION_REQUIRED));
    }

    private static NormalizedRequest baseExecutionRequest() {
        return new NormalizedRequest("do the thing", "target", List.of(), IntelligenceDepth.FAST,
                "output", List.of(), List.of(), "now", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE, false,
                null, null, "");
    }

    private static ExecutionWorkSpec defaultPlannerStep() {
        return new ExecutionWorkSpec("step-1", "objective text", "target", "test.capability",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY, List.of(), List.of());
    }
}
