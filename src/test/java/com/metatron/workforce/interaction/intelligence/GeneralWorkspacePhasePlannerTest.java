package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeneralWorkspacePhasePlannerTest {

    @Test
    void lifecycleHeavyGeneralEngineeringBecomesDurableProduceVerifyDeliverGraph() {
        ExecutionWorkSpec base = new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                "Build and deliver a complete runnable web application, run build and tests, "
                        + "perform runtime verification, create Git evidence and a pull request.",
                "repository:kelvinka38/example-app",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("application delivered"),
                List.of("workspace-source:fresh-new-application"));

        List<ExecutionWorkSpec> plan = GeneralWorkspacePhasePlanner.phase(base);

        assertEquals(3, plan.size());
        ExecutionWorkSpec produce = plan.get(0);
        ExecutionWorkSpec verify = plan.get(1);
        ExecutionWorkSpec deliver = plan.get(2);

        assertEquals("general-engineering-workspace-execution-produce", produce.stepId());
        assertTrue(produce.dependsOn().isEmpty());
        assertTrue(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_PRODUCE));
        assertTrue(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST));

        assertEquals(List.of(produce.stepId()), verify.dependsOn());
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_VERIFY));
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_BUILD));
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_TEST));
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));

        assertEquals(List.of(verify.stepId()), deliver.dependsOn());
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_DELIVER));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_VERIFY));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
    }

    @Test
    void simpleGeneralWorkspaceMutationStaysSingleStep() {
        ExecutionWorkSpec base = new ExecutionWorkSpec(
                "simple",
                "Write docs/proof.txt with the requested content.",
                "repository:kelvinka38/example",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("proof exists"),
                List.of("workspace evidence"));

        assertEquals(List.of(base), GeneralWorkspacePhasePlanner.phase(base));
    }
}
