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
    void onlyTheLastStepInPlanOrderIsRaisedToTheObjectiveFloorOrdinaryStepsAreUnaffected() {
        ExecutionWorkSpec prerequisiteStep = defaultPlannerStep();
        ExecutionWorkSpec buildStep = new ExecutionWorkSpec("step-2", "build", "target", "cap",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        ExecutionWorkSpec releaseOwnerStep = new ExecutionWorkSpec("step-3", "open the PR", "target", "cap",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING);

        NormalizedRequest request = baseExecutionRequest()
                .withCompletionPolicy(CompletionPolicy.PRODUCTION_REQUIRED)
                .withExecutionWorkPlan(List.of(prerequisiteStep, buildStep, releaseOwnerStep));

        List<ExecutionWorkSpec> plan = request.executionWorkPlan();
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, plan.get(0).completionPolicy(),
                "ordinary prerequisite steps must NOT be forced to carry their own release-evidence chain");
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, plan.get(1).completionPolicy());
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, plan.get(2).completionPolicy(),
                "only the deterministic release-owner (last) step is raised to the Objective floor");
    }

    @Test
    void singleStepPlanStillRaisesItsOnlyStepToTheFloor() {
        NormalizedRequest request = baseExecutionRequest()
                .withCompletionPolicy(CompletionPolicy.PR_REQUIRED)
                .withExecutionWorkPlan(List.of(defaultPlannerStep()));
        assertEquals(CompletionPolicy.PR_REQUIRED, request.executionWorkPlan().get(0).completionPolicy());
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
