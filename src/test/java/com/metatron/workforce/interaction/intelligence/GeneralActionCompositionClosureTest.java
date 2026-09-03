package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeneralActionCompositionClosureTest {
    @Test
    void missingRepositoryPatchCapabilityComposesIntoGeneralWorkspace() {
        ExecutionWorkSpec original = new ExecutionWorkSpec(
                "step-1", "repair repository defect", "kelvinka38/metatron-workforce",
                "repository.code.patch", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("defect fixed", "tests pass"), List.of("source-diff", "test-report"));
        ExecutionPlanProposalService raw = (caseId, request, capabilities) -> List.of(original);
        ExecutionPlanProposalService composed = new GeneralActionComposingExecutionPlanProposalService(raw);

        List<ExecutionWorkSpec> result = composed.propose(
                "case-1", null, List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY));

        assertEquals(1, result.size());
        ExecutionWorkSpec step = result.getFirst();
        assertEquals(GeneralWorkspaceAutonomousCapability.CAPABILITY, step.requiredCapability());
        assertTrue(step.evidenceRequirements().contains("requested-capability:repository.code.patch"));
        assertTrue(step.evidenceRequirements().contains("general-action-composition:repository.code.patch"));
        assertTrue(step.acceptanceCriteria().stream().anyMatch(value -> value.contains("repository.code.patch")));
    }

    @Test
    void plannerUnavailablePrefixStillComposesGeneralRepositoryActions() {
        List<ExecutionWorkSpec> originals = List.of(
                step("step-write", "UNAVAILABLE:repository.content.write"),
                step("step-test", "UNAVAILABLE:repository.test.run"),
                step("step-commit", "UNAVAILABLE:repository.commit.local"));
        ExecutionPlanProposalService raw = (caseId, request, capabilities) -> originals;
        ExecutionPlanProposalService composed = new GeneralActionComposingExecutionPlanProposalService(raw);

        List<ExecutionWorkSpec> result = composed.propose(
                "case-unavailable", null, List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY));

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(step ->
                GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(step.requiredCapability())));
        for (int i = 0; i < originals.size(); i++) {
            String requested = originals.get(i).requiredCapability();
            assertTrue(result.get(i).evidenceRequirements().contains("requested-capability:" + requested));
            assertTrue(result.get(i).evidenceRequirements().contains("general-action-composition:" + requested));
        }
    }

    @Test
    void unrelatedMissingCapabilityIsNotSilentlyComposed() {
        ExecutionWorkSpec original = new ExecutionWorkSpec(
                "step-2", "send external payment", "payment",
                "finance.payment.execute", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("payment settled"), List.of("settlement-observation"));
        ExecutionPlanProposalService raw = (caseId, request, capabilities) -> List.of(original);
        ExecutionPlanProposalService composed = new GeneralActionComposingExecutionPlanProposalService(raw);

        ExecutionWorkSpec result = composed.propose(
                "case-2", null, List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY)).getFirst();

        assertEquals("finance.payment.execute", result.requiredCapability());
        assertEquals(original, result);
    }

    private static ExecutionWorkSpec step(String id, String capability) {
        return new ExecutionWorkSpec(
                id, "general repository work", "kelvinka38/metatron-workforce",
                capability, List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("work completed"), List.of("runtime evidence"));
    }
}
