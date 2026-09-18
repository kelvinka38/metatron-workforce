package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralActionComposingExecutionPlanProposalServiceExplicitGeneralTest {

    @Test
    void explicitGeneralWorkspaceObjectiveCannotEscapeIntoSpecialAvailableCapabilities() {
        NormalizedRequest request = new NormalizedRequest(
                "Take ownership of one governed MUTATING engineering Objective using execution.general.workspace. "
                        + "Read src/main/java/com/metatron/Fix.java, replace the requested source text, run the full test suite, "
                        + "stage and create a local Git commit, then publish a reviewable pull request against main.",
                "kelvinka38/metatron-workforce",
                List.of("Do not modify any other source path"),
                IntelligenceDepth.FAST,
                "evidence-backed completion",
                List.of(),
                List.of("Do not merge"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                false,
                null,
                LlmProvider.OPENAI,
                "");

        List<ExecutionWorkSpec> plannerPlan = List.of(
                new ExecutionWorkSpec(
                        "materialize",
                        "Materialize the repository",
                        "kelvinka38/metatron-workforce",
                        GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        List.of(),
                        ExecutionWorkSpec.Consequence.READ_ONLY,
                        List.of("snapshot exists"),
                        List.of("snapshot evidence")),
                new ExecutionWorkSpec(
                        "special-pr",
                        "Use the bounded special PR proposer",
                        "kelvinka38/metatron-workforce",
                        "repository.pr.propose",
                        List.of("materialize"),
                        ExecutionWorkSpec.Consequence.MUTATING,
                        List.of("special PR exists"),
                        List.of("special fixture evidence")),
                new ExecutionWorkSpec(
                        "audit",
                        "Audit the repository",
                        "kelvinka38/metatron-workforce",
                        "repository.audit.read",
                        List.of("special-pr"),
                        ExecutionWorkSpec.Consequence.READ_ONLY,
                        List.of("audit complete"),
                        List.of("audit evidence")));

        GeneralActionComposingExecutionPlanProposalService service =
                new GeneralActionComposingExecutionPlanProposalService((caseId, normalized, capabilities) -> plannerPlan);

        List<ExecutionWorkSpec> result = service.propose(
                "case",
                request,
                List.of(
                        GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        "repository.pr.propose",
                        "repository.audit.read"));

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(work ->
                GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(work.requiredCapability())));
        assertEquals(List.of(), result.get(0).dependsOn());
        assertEquals(List.of(result.get(0).stepId()), result.get(1).dependsOn());
        assertEquals(List.of(result.get(1).stepId()), result.get(2).dependsOn());
        assertTrue(result.stream().allMatch(work -> work.objective().contains("src/main/java/com/metatron/Fix.java")));
        assertTrue(result.stream().allMatch(work -> work.objective().contains("Do not merge")));
        assertTrue(result.get(1).evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_TEST));
        assertTrue(result.get(2).evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
        assertTrue(result.stream().allMatch(work -> work.evidenceRequirements().contains(
                GeneralActionComposingExecutionPlanProposalService.GENERAL_RUNTIME_MARKER)));
        assertFalse(result.stream().anyMatch(step -> "repository.pr.propose".equals(step.requiredCapability())));
        assertFalse(result.stream().anyMatch(step -> "repository.audit.read".equals(step.requiredCapability())));
    }

    @Test
    void explicitGeneralWorkspaceWithoutRequestedProviderBypassesFrontierPlanner() {
        NormalizedRequest request = new NormalizedRequest(
                "Take ownership of one governed general engineering Objective using execution.general.workspace. "
                        + "Find the root cause, fix the defect, run tests, create one local Git commit, "
                        + "publish a reviewable unmerged pull request, and verify the acceptance criteria.",
                "kelvinka38/metatron-workforce",
                List.of("Do not weaken fixture tests"),
                IntelligenceDepth.FAST,
                "evidence-backed completion",
                List.of(),
                List.of("Do not merge"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                false,
                null,
                null,
                "");

        AtomicBoolean delegateCalled = new AtomicBoolean(false);
        GeneralActionComposingExecutionPlanProposalService service =
                new GeneralActionComposingExecutionPlanProposalService((caseId, normalized, capabilities) -> {
                    delegateCalled.set(true);
                    throw new IllegalStateException("frontier planner must not be required");
                });

        List<ExecutionWorkSpec> result = service.propose(
                "case",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY));

        assertFalse(delegateCalled.get());
        assertEquals(3, result.size());
        assertEquals("explicit-general-workspace-produce", result.get(0).stepId());
        assertEquals("explicit-general-workspace-verify", result.get(1).stepId());
        assertEquals("explicit-general-workspace-deliver", result.get(2).stepId());
        assertTrue(result.stream().allMatch(work ->
                GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(work.requiredCapability())));
        assertEquals(List.of(), result.get(0).dependsOn());
        assertEquals(List.of(result.get(0).stepId()), result.get(1).dependsOn());
        assertEquals(List.of(result.get(1).stepId()), result.get(2).dependsOn());
        assertTrue(result.get(1).evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_TEST));
        assertTrue(result.get(2).evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT));
        assertTrue(result.get(2).evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
        assertTrue(result.stream().allMatch(work -> work.evidenceRequirements().contains(
                GeneralActionComposingExecutionPlanProposalService.GENERAL_RUNTIME_MARKER)));
    }

    @Test
    void ordinaryExecutionRequestStillKeepsAvailableSpecialCapabilityPlan() {
        NormalizedRequest request = new NormalizedRequest(
                "Open the approved bounded autonomy gap-matrix proposal",
                "kelvinka38/metatron-workforce",
                List.of(),
                IntelligenceDepth.FAST,
                "proposal",
                List.of(),
                List.of("Do not merge"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                false,
                null,
                LlmProvider.OPENAI,
                "");

        List<ExecutionWorkSpec> plannerPlan = List.of(
                new ExecutionWorkSpec(
                        "special-pr",
                        "Use the approved bounded PR proposer",
                        "kelvinka38/metatron-workforce",
                        "repository.pr.propose",
                        List.of(),
                        ExecutionWorkSpec.Consequence.MUTATING,
                        List.of("PR exists"),
                        List.of("PR evidence")));

        GeneralActionComposingExecutionPlanProposalService service =
                new GeneralActionComposingExecutionPlanProposalService((caseId, normalized, capabilities) -> plannerPlan);

        List<ExecutionWorkSpec> result = service.propose(
                "case",
                request,
                List.of(
                        GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        "repository.pr.propose"));

        assertEquals(plannerPlan, result);
    }
}