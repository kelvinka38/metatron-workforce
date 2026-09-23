package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeneralWorkspacePhasePlannerTest {

    @Test
    void lifecycleHeavyGeneralEngineeringBecomesDurableProducePrepareVerifyDeliverGraph() {
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

        assertEquals(4, plan.size());
        ExecutionWorkSpec produce = plan.get(0);
        ExecutionWorkSpec prepare = plan.get(1);
        ExecutionWorkSpec verify = plan.get(2);
        ExecutionWorkSpec deliver = plan.get(3);

        assertEquals("general-engineering-workspace-execution-produce", produce.stepId());
        assertTrue(produce.dependsOn().isEmpty());
        assertTrue(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_PRODUCE));
        assertFalse(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST));
        assertFalse(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_BUILD));
        assertFalse(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_TEST));
        assertFalse(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));
        assertFalse(produce.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT));

        assertEquals("general-engineering-workspace-execution-prepare", prepare.stepId());
        assertEquals(List.of(produce.stepId()), prepare.dependsOn());
        assertTrue(prepare.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_PREPARE));
        assertTrue(prepare.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST));
        assertFalse(prepare.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_BUILD));
        assertFalse(prepare.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_TEST));
        assertFalse(prepare.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));
        assertFalse(prepare.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT));

        assertEquals(List.of(prepare.stepId()), verify.dependsOn());
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_VERIFY));
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_BUILD));
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_TEST));
        assertTrue(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));
        assertFalse(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST));
        assertFalse(verify.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT));

        assertEquals(List.of(verify.stepId()), deliver.dependsOn());
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_DELIVER));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_VERIFY));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
        assertFalse(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST));
        assertFalse(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_BUILD));
        assertFalse(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_TEST));
        assertFalse(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));
    }

    @Test
    void freshNewApplicationAlwaysRequiresGithubPublishEvenWithoutExplicitPublishWording() {
        // Root-cause fix (2026-09-23, Founder-reported): "build me a runnable web app called Acme" never
        // says "pull request" or "publish"+"github", so the old wording-only publish heuristic left the
        // completed application's DELIVER phase without any GITHUB_PR requirement -- the work finished
        // but had no possible path to Human-visible output. A brand-new named application must always
        // require a publishable PR, regardless of the exact words the Human used.
        ExecutionWorkSpec base = new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                "Build and deliver a complete runnable web application called Acme, run build and tests, "
                        + "perform runtime verification and create Git evidence.",
                "repository:kelvinka38/acme",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("application delivered"),
                List.of("workspace-source:fresh-new-application"));

        List<ExecutionWorkSpec> plan = GeneralWorkspacePhasePlanner.phase(base);

        assertEquals(4, plan.size());
        ExecutionWorkSpec deliver = plan.get(3);
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT));
        assertTrue(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GIT_VERIFY));
    }

    @Test
    void nonFreshLifecycleHeavyWorkWithoutExplicitPublishWordingStaysWithoutGithubRequirement() {
        // Scope guard: the broadened publish requirement is tied to the deterministic fresh-new-application
        // signal, not to lifecycle-heaviness in general -- ordinary work against an existing, already
        // Human-visible repository must not be silently forced to open a PR it never asked for.
        ExecutionWorkSpec base = new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                "Build and deliver a fix to the existing service, run build and tests, "
                        + "perform runtime verification and create Git evidence.",
                "repository:kelvinka38/metatron-workforce",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("fix delivered"),
                List.of());

        List<ExecutionWorkSpec> plan = GeneralWorkspacePhasePlanner.phase(base);

        ExecutionWorkSpec deliver = plan.get(plan.size() - 1);
        assertFalse(deliver.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
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
