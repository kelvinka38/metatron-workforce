package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralCognitiveWorkerBrainPreconditionTest {
    private static final String SHA = "3e86d4e2876a90c580383d5c1de48360b5049b3b";

    @Test
    void exactRepositorySnapshotMustMaterializeBeforeProviderSelectsGitActions() {
        ExecutionWorkSpec work = exactSnapshotWork();
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.git.run"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(context);

        assertEquals("workspace.repository.materialize", thought.actionRef());
        assertEquals("kelvinka38/metatron-workforce", thought.inputs().get("repository"));
        assertEquals(SHA, thought.inputs().get("ref"));
    }

    @Test
    void canonicalRepositoryTargetPrefixIsStrippedForMaterialization() {
        // Authority target shape fix: the governed target now carries the canonical
        // "repository:owner/repo" shape SotDiscoveryService/AuthorityManifestCatalog resolve (matching
        // its repository:* authority wildcard by prefix) instead of a bare "owner/repo". Materialization
        // must still recover the bare repository locator from that canonical representation.
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "checkout_target_snapshot",
                "Materialize repository snapshot at specific commit " + SHA,
                "repository:kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Workspace contains source tree for commit " + SHA),
                List.of("git rev-parse HEAD matches " + SHA));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.git.run"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(context);

        assertEquals("workspace.repository.materialize", thought.actionRef());
        assertEquals("kelvinka38/metatron-workforce", thought.inputs().get("repository"));
        assertEquals(SHA, thought.inputs().get("ref"));
    }

    @Test
    void readOnlyGitIdentityRequirementUsesGovernedStatusInspectionAfterMaterialization() {
        ExecutionWorkSpec work = exactSnapshotWork();
        CognitiveWorkerRuntime.Cycle materialized = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought("workspace.repository.materialize",
                        Map.of("repository", "kelvinka38/metatron-workforce", "ref", SHA), "materialize"),
                ActionFabric.ActionObservation.success("workspace.repository.materialize", "materialized", Map.of(), List.of("repo")),
                CognitiveWorkerRuntime.Reflection.continueWith("verify identity"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.git.status"), List.of(materialized), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.readOnlyGitInspectionPrecondition(context);

        assertEquals("workspace.git.status", thought.actionRef());
        assertEquals(Map.of(), thought.inputs());
    }

    @Test
    void successfulGitIdentityInspectionIsNotRepeated() {
        ExecutionWorkSpec work = exactSnapshotWork();
        CognitiveWorkerRuntime.Cycle materialized = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought("workspace.repository.materialize", Map.of("repository", "kelvinka38/metatron-workforce"), "materialize"),
                ActionFabric.ActionObservation.success("workspace.repository.materialize", "materialized", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("verify"));
        CognitiveWorkerRuntime.Cycle inspected = new CognitiveWorkerRuntime.Cycle(
                2,
                new CognitiveWorkerRuntime.Thought("workspace.git.status", Map.of(), "inspect HEAD"),
                ActionFabric.ActionObservation.success("workspace.git.status", "inspected", Map.of("headSha", SHA), List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("continue"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.git.status"), List.of(materialized, inspected), Map.of());

        assertNull(GeneralCognitiveWorkerBrain.readOnlyGitInspectionPrecondition(context));
    }

    @Test
    void singleStepMutatingRepositoryWorkMustMaterializeBeforeEditing() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "repair",
                "Repair a defect, run tests, commit the change, and open a pull request",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "reviewable pull request exists"), List.of("runtime evidence"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(context);
        assertEquals("workspace.repository.materialize", thought.actionRef());
        assertEquals("kelvinka38/metatron-workforce", thought.inputs().get("repository"));
    }


    @Test
    void explicitExactShaFileWriteExecutesAndReflectsWithoutProvider() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-file-write",
                "Create or replace only docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md with a short proof containing exact source SHA "
                        + SHA + ". Exact UTF-8 content: source_sha=" + SHA,
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of("general-snapshot"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("proof file exists and contains exact source SHA " + SHA),
                List.of("successful workspace.file.write"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.file.write"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.governedExactShaFileWritePrecondition(context);

        assertEquals("workspace.file.write", thought.actionRef());
        assertEquals("docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md", thought.inputs().get("path"));
        assertEquals("source_sha=" + SHA + "\n", thought.inputs().get("content"));

        CognitiveWorkerRuntime.Reflection reflection =
                GeneralCognitiveWorkerBrain.governedRequiredActionReflection(
                        context,
                        ActionFabric.ActionObservation.success(
                                "workspace.file.write", "written", Map.of(), List.of("file-evidence")));

        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, reflection.decision());
    }

    @Test
    void explicitTestRequirementForcesGovernedTestAfterMaterialization() {
        ExecutionWorkSpec work = exactSnapshotAndTestWork();
        CognitiveWorkerRuntime.Cycle materialized = successfulCycle(
                1, "workspace.repository.materialize", Map.of("repository", "kelvinka38/metatron-workforce"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.test.run"), List.of(materialized), Map.of());

        CognitiveWorkerRuntime.Thought thought = GeneralCognitiveWorkerBrain.governedTestPrecondition(context);

        assertEquals("workspace.test.run", thought.actionRef());
        assertEquals(Map.of(), thought.inputs());
    }

    @Test
    void failedReadOnlyTestIsNotDeterministicallyRepeated() {
        ExecutionWorkSpec work = exactSnapshotAndTestWork();
        CognitiveWorkerRuntime.Cycle failed = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought("workspace.test.run", Map.of(), "verify"),
                ActionFabric.ActionObservation.failure("workspace.test.run", "test failed", List.of("test-failure")),
                CognitiveWorkerRuntime.Reflection.continueWith("inspect failure"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.test.run", "workspace.file.search", "workspace.file.read"),
                List.of(failed), Map.of("workspaceMaterialized", "true"));

        assertNull(GeneralCognitiveWorkerBrain.governedTestPrecondition(context),
                "a failed dedicated READ_ONLY test must return control to cognition instead of blind-repeating");
    }

    @Test
    void successfulGovernedTestIsNotRepeatedAndPermitsCompletion() {
        ExecutionWorkSpec work = exactSnapshotAndTestWork();
        CognitiveWorkerRuntime.Cycle materialized = successfulCycle(
                1, "workspace.repository.materialize", Map.of("repository", "kelvinka38/metatron-workforce"));
        CognitiveWorkerRuntime.Cycle tested = successfulCycle(2, "workspace.test.run", Map.of());
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.test.run"),
                List.of(materialized, tested), Map.of());

        assertNull(GeneralCognitiveWorkerBrain.governedTestPrecondition(context));
        CognitiveWorkerRuntime.Reflection completion = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.git.status", "verified", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, completion.decision());
    }

    @Test
    void providerCannotCompleteExplicitTestWorkWithoutSuccessfulTestObservation() {
        ExecutionWorkSpec work = exactSnapshotAndTestWork();
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.test.run"), List.of(), Map.of());

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.repository.materialize", "materialized", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
    }

    @Test
    void freshDeliveryPhaseInitializesGitDeterministicallyBeforeAdd() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "deliver",
                "DELIVER PHASE. Commit carried work product and verify Git state.",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of("verify"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("commit exists"),
                List.of(
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.PHASE_DELIVER,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_GIT_VERIFY));
        CognitiveWorkerRuntime.CognitiveContext beforeInit = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(),
                Map.of("workspaceGitInitialized", "false"));

        CognitiveWorkerRuntime.Thought init = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeInit);

        assertEquals("workspace.git.run", init.actionRef());
        assertEquals("[\"init\"]", init.inputs().get("argsJson"));

        CognitiveWorkerRuntime.Cycle initialized = successfulCycle(1, "workspace.git.run", init.inputs());
        CognitiveWorkerRuntime.CognitiveContext beforeAdd = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(initialized),
                Map.of("workspaceGitInitialized", "false"));

        CognitiveWorkerRuntime.Thought add = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeAdd);

        assertEquals("workspace.git.run", add.actionRef());
        assertEquals("[\"add\",\"-A\"]", add.inputs().get("argsJson"));
    }

    @Test
    void preparePhaseUsesDeterministicProjectPrepareActionAndCountsItsObservation() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "prepare",
                "PREPARE PHASE. Create a project manifest for the carried application source.",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of("produce"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("manifest exists"),
                List.of(
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.PHASE_PREPARE,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.project.prepare"), List.of(),
                Map.of(
                        "workspacePrimarySourcePath", "src/App.js",
                        "workspacePrimarySourcePreview", "import React from 'react';"));

        CognitiveWorkerRuntime.Thought prepare =
                GeneralCognitiveWorkerBrain.governedProjectPreparePrecondition(context);
        assertEquals("workspace.project.prepare", prepare.actionRef());
        assertTrue(prepare.inputs().isEmpty());

        CognitiveWorkerRuntime.Reflection complete = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success(
                        "workspace.project.prepare", "prepared",
                        Map.of("manifestPath", "package.json", "projectKind", "node-react"), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("prepared"));
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, complete.decision());

        CognitiveWorkerRuntime.CognitiveContext providerContext =
                GeneralCognitiveWorkerBrain.providerActionSelectionContext(context);
        assertEquals(List.of("workspace.project.prepare"), providerContext.availableActions());
    }

    @Test
    void verifyPhaseOrdersDependenciesBuildTestAndBoundedRuntimeProbe() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "verify",
                "VERIFY PHASE. Run governed build, tests, and runtime verification.",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of("prepare"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("build passes", "tests pass", "runtime passes"),
                List.of(
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.PHASE_VERIFY,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_BUILD,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_TEST,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));
        List<String> actions = List.of(
                "workspace.dependencies.install", "workspace.build.run",
                "workspace.test.run", "workspace.process.run");
        Map<String, String> memory = Map.of(
                "workspaceDependencyInstallRequired", "true",
                "workspaceProjectKind", "node-react");

        CognitiveWorkerRuntime.CognitiveContext empty = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                actions, List.of(), memory);
        assertEquals("workspace.dependencies.install",
                GeneralCognitiveWorkerBrain.governedDependencyPrecondition(empty).actionRef());
        assertNull(GeneralCognitiveWorkerBrain.governedBuildPrecondition(empty));
        assertNull(GeneralCognitiveWorkerBrain.governedTestPrecondition(empty));
        assertNull(GeneralCognitiveWorkerBrain.governedRuntimePrecondition(empty));

        CognitiveWorkerRuntime.Cycle deps = successfulCycle(1, "workspace.dependencies.install", Map.of());
        CognitiveWorkerRuntime.CognitiveContext afterDeps = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                actions, List.of(deps), memory);
        assertEquals("workspace.build.run",
                GeneralCognitiveWorkerBrain.governedBuildPrecondition(afterDeps).actionRef());

        CognitiveWorkerRuntime.Cycle build = successfulCycle(2, "workspace.build.run", Map.of());
        CognitiveWorkerRuntime.CognitiveContext afterBuild = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                actions, List.of(deps, build), memory);
        assertEquals("workspace.test.run",
                GeneralCognitiveWorkerBrain.governedTestPrecondition(afterBuild).actionRef());

        CognitiveWorkerRuntime.Cycle test = successfulCycle(3, "workspace.test.run", Map.of());
        CognitiveWorkerRuntime.CognitiveContext afterTest = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                actions, List.of(deps, build, test), memory);
        CognitiveWorkerRuntime.Thought runtime = GeneralCognitiveWorkerBrain.governedRuntimePrecondition(afterTest);
        assertEquals("workspace.process.run", runtime.actionRef());
        assertEquals("node", runtime.inputs().get("executable"));
        assertTrue(runtime.inputs().get("argsJson").contains("dist/index.html"));
    }

    @Test
    void plainNodeKindGetsADeterministicRuntimeProbeInsteadOfBeingLeftToCognitiveFreehand() {
        // Regression test for a real production failure (case-c3c7f22f-1bca-451d-a42c-47499cdcd74c):
        // a plain (non-react) Node workspace had no deterministic runtime-check script here, so cognition
        // was forced to freehand workspace.process.run's argsJson every cycle; the real LLM produced a
        // malformed argsJson twice identically, tripping the repeated-failure circuit breaker and
        // permanently blocking the Objective at VERIFY.
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "verify",
                "VERIFY PHASE. Run governed build, tests, and runtime verification.",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of("prepare"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "runtime passes"),
                List.of(
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.PHASE_VERIFY,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_TEST,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));
        List<String> actions = List.of("workspace.test.run", "workspace.process.run");
        Map<String, String> memory = Map.of(
                "workspaceProjectKind", "node",
                "workspaceProjectManifestPreview",
                "{\"name\":\"control-center\",\"scripts\":{\"start\":\"node server.js\",\"test\":\"echo ok\"}}");

        CognitiveWorkerRuntime.Cycle test = successfulCycle(1, "workspace.test.run", Map.of());
        CognitiveWorkerRuntime.CognitiveContext afterTest = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                actions, List.of(test), memory);

        CognitiveWorkerRuntime.Thought runtime = GeneralCognitiveWorkerBrain.governedRuntimePrecondition(afterTest);

        assertEquals("workspace.process.run", runtime.actionRef());
        assertEquals("node", runtime.inputs().get("executable"));
        String argsJson = runtime.inputs().get("argsJson");
        assertTrue(argsJson.startsWith("[\"-e\",\""), argsJson);
        assertTrue(argsJson.contains("server.js"), argsJson);
        assertTrue(argsJson.contains("child_process"), argsJson);
    }

    @Test
    void plainNodeKindWithNoParseableEntryPointIsLeftToCognitionRatherThanGuessed() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "verify",
                "VERIFY PHASE. Run governed build, tests, and runtime verification.",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of("prepare"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "runtime passes"),
                List.of(
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.PHASE_VERIFY,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_TEST,
                        com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME));
        List<String> actions = List.of("workspace.test.run", "workspace.process.run");
        Map<String, String> memory = Map.of(
                "workspaceProjectKind", "node",
                "workspaceProjectManifestPreview",
                "{\"name\":\"control-center\",\"scripts\":{\"start\":\"pm2 start ecosystem.config.js\"}}");

        CognitiveWorkerRuntime.Cycle test = successfulCycle(1, "workspace.test.run", Map.of());
        CognitiveWorkerRuntime.CognitiveContext afterTest = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                actions, List.of(test), memory);

        assertNull(GeneralCognitiveWorkerBrain.governedRuntimePrecondition(afterTest));
    }

    @Test
    void dedicatedCommitStepStagesOnlyBoundedPathFromWorkWithoutStepLocalMutationHistory() {
        ExecutionWorkSpec work = stageAndCommitWork();
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(),
                Map.of("workspaceMaterialized", "true"));

        CognitiveWorkerRuntime.Thought add = GeneralCognitiveWorkerBrain.governedGitPrecondition(context);

        assertEquals("workspace.git.run", add.actionRef());
        assertEquals("[\"add\",\"docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md\"]",
                add.inputs().get("argsJson"));
    }

    @Test
    void stageAndCommitWorkForcesBothGovernedGitSubactionsInOrder() {
        ExecutionWorkSpec work = stageAndCommitWork();
        CognitiveWorkerRuntime.Cycle written = successfulCycle(
                1, "workspace.file.write", Map.of("path", "docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md"));
        CognitiveWorkerRuntime.CognitiveContext beforeAdd = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(written), Map.of());

        CognitiveWorkerRuntime.Thought add = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeAdd);

        assertEquals("workspace.git.run", add.actionRef());
        assertEquals("[\"add\",\"docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md\"]",
                add.inputs().get("argsJson"));

        CognitiveWorkerRuntime.Cycle added = successfulCycle(2, "workspace.git.run", add.inputs());
        CognitiveWorkerRuntime.CognitiveContext beforeCommit = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(added), Map.of());

        CognitiveWorkerRuntime.Thought commit = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeCommit);

        assertEquals("workspace.git.run", commit.actionRef());
        assertEquals(true, commit.inputs().get("argsJson").startsWith("[\"commit\",\"-m\","));

        CognitiveWorkerRuntime.Cycle committed = successfulCycle(3, "workspace.git.run", commit.inputs());
        CognitiveWorkerRuntime.CognitiveContext beforeVerification = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(added, committed), Map.of());

        CognitiveWorkerRuntime.Thought verification =
                GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeVerification);

        assertEquals("workspace.git.status", verification.actionRef());
    }

    @Test
    void providerCannotCompleteStageAndCommitWorkAfterAddAlone() {
        ExecutionWorkSpec work = stageAndCommitWork();
        Map<String, String> addInputs = Map.of(
                "argsJson", "[\"add\",\"docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md\"]");
        CognitiveWorkerRuntime.Cycle added = successfulCycle(1, "workspace.git.run", addInputs);
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run"), List.of(added), Map.of());

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.git.status", "staged", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
    }

    @Test
    void providerMayCompleteStageAndCommitWorkAfterCommitVerification() {
        ExecutionWorkSpec work = stageAndCommitWork();
        CognitiveWorkerRuntime.Cycle added = successfulCycle(1, "workspace.git.run", Map.of(
                "argsJson", "[\"add\",\"docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md\"]"));
        CognitiveWorkerRuntime.Cycle committed = successfulCycle(2, "workspace.git.run", Map.of(
                "argsJson", "[\"commit\",\"-m\",\"Complete governed Objective work step\"]"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(added, committed), Map.of());

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.git.status", "verified", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, guarded.decision());
    }

    @Test
    void governedGitReflectionCannotFinishBeforeCommitAndVerification() {
        ExecutionWorkSpec work = stageAndCommitWork();
        CognitiveWorkerRuntime.CognitiveContext beforeAdd = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(), Map.of());
        ActionFabric.ActionObservation gitSuccess =
                ActionFabric.ActionObservation.success("workspace.git.run", "success", Map.of(), List.of());

        CognitiveWorkerRuntime.Reflection afterAdd =
                GeneralCognitiveWorkerBrain.governedRequiredActionReflection(beforeAdd, gitSuccess);

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, afterAdd.decision());

        CognitiveWorkerRuntime.Cycle added = successfulCycle(1, "workspace.git.run", Map.of(
                "argsJson", "[\"add\",\"docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md\"]"));
        CognitiveWorkerRuntime.CognitiveContext beforeCommit = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(added), Map.of());

        CognitiveWorkerRuntime.Reflection afterCommit =
                GeneralCognitiveWorkerBrain.governedRequiredActionReflection(beforeCommit, gitSuccess);

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, afterCommit.decision());
    }

    @Test
    void remoteProposalCannotRunUntilLatestMutationIsTestedAndCommitted() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "repair-and-publish",
                "Repair the defect, run tests, commit the change, and open a pull request",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "reviewable pull request exists"),
                List.of("general Action Fabric and fresh GitHub Observation"));

        CognitiveWorkerRuntime.Cycle materialized = successfulCycle(
                1, "workspace.repository.materialize", Map.of("repository", "kelvinka38/metatron-workforce"));
        CognitiveWorkerRuntime.Cycle written = successfulCycle(
                2, "workspace.file.write", Map.of("path", "src/main/java/example.java"));
        CognitiveWorkerRuntime.Cycle tested = successfulCycle(3, "workspace.test.run", Map.of());
        CognitiveWorkerRuntime.Cycle added = successfulCycle(
                4, "workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]"));
        CognitiveWorkerRuntime.Cycle committed = successfulCycle(
                5, "workspace.git.run", Map.of("argsJson", "[\"commit\",\"-m\",\"Complete governed Objective work step\"]"));
        CognitiveWorkerRuntime.CognitiveContext ready = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write", "workspace.test.run",
                        "workspace.git.run", "workspace.github.pr.publish"),
                List.of(materialized, written, tested, added, committed), Map.of());

        CognitiveWorkerRuntime.Thought proposal =
                GeneralCognitiveWorkerBrain.governedRemoteProposalPrecondition(ready);
        assertEquals("workspace.github.pr.publish", proposal.actionRef());

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                ready,
                ActionFabric.ActionObservation.success("workspace.git.status", "verified", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));
        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
    }

    @Test
    void successfulRemotePublicationCanCloseFreshPublishStepBecausePublisherProvesCommittedWorkspace() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "publish",
                "Publish the committed Objective workspace as a reviewable pull request",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of("commit"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("reviewable pull request exists"),
                List.of("fresh GitHub Observation"));
        CognitiveWorkerRuntime.CognitiveContext freshPublishStep = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.github.pr.publish"), List.of(), Map.of(
                        "workspaceMaterialized", "true",
                        "repository", "kelvinka38/metatron-workforce"));

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                freshPublishStep,
                ActionFabric.ActionObservation.success(
                        "workspace.github.pr.publish", "published", Map.of("pullRequestUrl", "https://example.test/pr/1"), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, guarded.decision());
    }

    @Test
    void vagueEngineeringLeavesProjectScopedTestSelectionToCognition() {
        ExecutionWorkSpec work = iterativeRepairWork();
        CognitiveWorkerRuntime.Cycle patched = successfulCycle(
                1, "workspace.file.patch",
                Map.of("path", "acceptance/example/src/main/App.java",
                        "oldText", "broken", "newText", "attempt-one"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.file.search", "workspace.file.read", "workspace.file.patch", "workspace.test.run"),
                List.of(patched), Map.of("workspaceMaterialized", "true"));

        assertNull(GeneralCognitiveWorkerBrain.governedTestPrecondition(context),
                "unknown/nested engineering must let cognition choose the project workingDirectory");
    }

    @Test
    void completionGuardRequiresFreshSuccessfulTestAfterLatestVagueRepairMutation() {
        ExecutionWorkSpec work = iterativeRepairWork();
        CognitiveWorkerRuntime.Cycle firstPatch = successfulCycle(
                1, "workspace.file.patch",
                Map.of("path", "acceptance/example/app.py", "oldText", "broken", "newText", "attempt-one"));
        CognitiveWorkerRuntime.Cycle failedTest = new CognitiveWorkerRuntime.Cycle(
                2,
                new CognitiveWorkerRuntime.Thought(
                        "workspace.test.run", Map.of("workingDirectory", "acceptance/example"), "verify first repair"),
                ActionFabric.ActionObservation.failure(
                        "workspace.test.run", "tests still fail", List.of("test-failure")),
                CognitiveWorkerRuntime.Reflection.continueWith("repair again"));
        CognitiveWorkerRuntime.Cycle secondPatch = successfulCycle(
                3, "workspace.file.patch",
                Map.of("path", "acceptance/example/app.py", "oldText", "attempt-one", "newText", "fixed"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.file.search", "workspace.file.read", "workspace.file.patch", "workspace.test.run"),
                List.of(firstPatch, failedTest, secondPatch), Map.of("workspaceMaterialized", "true"));

        assertNull(GeneralCognitiveWorkerBrain.governedTestPrecondition(context),
                "a new vague repair still leaves the correct project scope to cognition");

        CognitiveWorkerRuntime.Reflection premature = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success(
                        "workspace.file.search", "diagnostic search complete", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));
        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, premature.decision());

        CognitiveWorkerRuntime.Reflection verified = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success(
                        "workspace.test.run", "fixture tests pass", Map.of("workingDirectory", "acceptance/example"), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, verified.decision());
    }

    @Test
    void independentObservationVerificationDoesNotInventExtraGitStatusRequirement() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "repair-and-publish",
                "Find and repair the defect, run tests, create one local Git commit, publish a reviewable unmerged pull request, "
                        + "and verify every acceptance criterion through independent Observation. Do not merge.",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "reviewable pull request exists", "independent Observation verifies outcome"),
                List.of("general Action Fabric action journal", "fresh authoritative GitHub API Observation"));

        CognitiveWorkerRuntime.Cycle materialized = successfulCycle(
                1, "workspace.repository.materialize", Map.of("repository", "kelvinka38/metatron-workforce"));
        CognitiveWorkerRuntime.Cycle searched = successfulCycle(
                2, "workspace.file.search", Map.of("query", "defect"));
        CognitiveWorkerRuntime.Cycle read = successfulCycle(
                3, "workspace.file.read", Map.of("path", "src/App.java"));
        CognitiveWorkerRuntime.Cycle patched = successfulCycle(
                4, "workspace.file.patch", Map.of("path", "src/App.java", "oldText", "broken", "newText", "fixed"));
        CognitiveWorkerRuntime.Cycle tested = successfulCycle(
                5, "workspace.test.run", Map.of("workingDirectory", ""));
        CognitiveWorkerRuntime.Cycle added = successfulCycle(
                6, "workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]"));
        CognitiveWorkerRuntime.Cycle committed = successfulCycle(
                7, "workspace.git.run", Map.of("argsJson", "[\"commit\",\"-m\",\"repair\"]"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.search", "workspace.file.read",
                        "workspace.file.patch", "workspace.test.run", "workspace.git.run",
                        "workspace.git.status", "workspace.github.pr.publish"),
                List.of(materialized, searched, read, patched, tested, added, committed),
                Map.of("workspaceMaterialized", "true"));

        assertNull(GeneralCognitiveWorkerBrain.governedGitPrecondition(context),
                "Observation verification must not be reinterpreted as an extra Git-status requirement");

        ActionFabric.ActionObservation published = ActionFabric.ActionObservation.success(
                "workspace.github.pr.publish",
                "published",
                Map.of("pullRequestUrl", "https://github.com/example/repo/pull/1"),
                List.of("github-general-proposal:true"));
        CognitiveWorkerRuntime.Reflection required =
                GeneralCognitiveWorkerBrain.governedRequiredActionReflection(context, published);
        CognitiveWorkerRuntime.Reflection closed =
                GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(context, published, required);

        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, closed.decision());
    }

    @Test
    void explicitGitVerificationStillRequiresGovernedGitStatusAfterCommit() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "repair-and-verify-commit",
                "Repair the defect, run tests, create one local Git commit, then verify the commit with git status.",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "commit exists and is verified"),
                List.of("governed Git verification"));

        CognitiveWorkerRuntime.Cycle patched = successfulCycle(
                1, "workspace.file.patch", Map.of("path", "src/App.java", "oldText", "broken", "newText", "fixed"));
        CognitiveWorkerRuntime.Cycle tested = successfulCycle(2, "workspace.test.run", Map.of());
        CognitiveWorkerRuntime.Cycle added = successfulCycle(
                3, "workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]"));
        CognitiveWorkerRuntime.Cycle committed = successfulCycle(
                4, "workspace.git.run", Map.of("argsJson", "[\"commit\",\"-m\",\"repair\"]"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.file.patch", "workspace.test.run", "workspace.git.run", "workspace.git.status"),
                List.of(patched, tested, added, committed), Map.of("workspaceMaterialized", "true"));

        CognitiveWorkerRuntime.Thought status = GeneralCognitiveWorkerBrain.governedGitPrecondition(context);

        assertEquals("workspace.git.status", status.actionRef());
    }

    @Test
    void exactSourceReplacementCannotJumpFromMaterializationToRemotePublish() {
        ExecutionWorkSpec work = exactReplacementAndPublishWork();
        CognitiveWorkerRuntime.Cycle materialized = successfulCycle(
                1, "workspace.repository.materialize",
                Map.of("repository", "kelvinka38/metatron-workforce", "ref", SHA));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.read", "workspace.file.write",
                        "workspace.test.run", "workspace.git.run", "workspace.git.status",
                        "workspace.github.pr.publish"),
                List.of(materialized), Map.of());

        CognitiveWorkerRuntime.Thought read =
                GeneralCognitiveWorkerBrain.governedExactTextReplacementPrecondition(context);

        assertEquals("workspace.file.read", read.actionRef());
        assertEquals("src/main/java/com/metatron/workforce/action/GeneralCognitiveWorkerBrainFactory.java",
                read.inputs().get("path"));
        assertNull(GeneralCognitiveWorkerBrain.governedRemoteProposalPrecondition(context));
    }

    @Test
    void exactSourceReplacementExecutesDeterministicReadWriteTestCommitVerifyPublishSequence() {
        ExecutionWorkSpec work = exactReplacementAndPublishWork();
        String path = "src/main/java/com/metatron/workforce/action/GeneralCognitiveWorkerBrainFactory.java";
        String oldText = "Produces one stateful provider-backed brain per Cognitive Worker execution.";
        String newText = "Produces one stateful provider-backed brain for each governed Cognitive Worker execution.";
        CognitiveWorkerRuntime.Cycle materialized = successfulCycle(
                1, "workspace.repository.materialize",
                Map.of("repository", "kelvinka38/metatron-workforce", "ref", SHA));
        CognitiveWorkerRuntime.Cycle read = new CognitiveWorkerRuntime.Cycle(
                2,
                new CognitiveWorkerRuntime.Thought("workspace.file.read", Map.of("path", path), "read"),
                ActionFabric.ActionObservation.success(
                        "workspace.file.read", "read",
                        Map.of("path", path, "content",
                                "package sample;\n/** " + oldText + " */\nfinal class Sample {}\n"),
                        List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("replace"));
        CognitiveWorkerRuntime.CognitiveContext beforeWrite = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.read", "workspace.file.write",
                        "workspace.test.run", "workspace.git.run", "workspace.git.status",
                        "workspace.github.pr.publish"),
                List.of(materialized, read), Map.of());

        CognitiveWorkerRuntime.Thought write =
                GeneralCognitiveWorkerBrain.governedExactTextReplacementPrecondition(beforeWrite);

        assertEquals("workspace.file.write", write.actionRef());
        assertEquals(path, write.inputs().get("path"));
        assertTrue(write.inputs().get("content").contains(newText));
        assertTrue(!write.inputs().get("content").contains(oldText));

        CognitiveWorkerRuntime.Cycle written = successfulCycle(3, "workspace.file.write", write.inputs());
        CognitiveWorkerRuntime.CognitiveContext beforeTest = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.read", "workspace.file.write",
                        "workspace.test.run", "workspace.git.run", "workspace.git.status",
                        "workspace.github.pr.publish"),
                List.of(materialized, read, written), Map.of());
        assertEquals("workspace.test.run",
                GeneralCognitiveWorkerBrain.governedTestPrecondition(beforeTest).actionRef());

        CognitiveWorkerRuntime.Cycle tested = successfulCycle(4, "workspace.test.run", Map.of());
        CognitiveWorkerRuntime.CognitiveContext beforeAdd = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.read", "workspace.file.write",
                        "workspace.test.run", "workspace.git.run", "workspace.git.status",
                        "workspace.github.pr.publish"),
                List.of(materialized, read, written, tested), Map.of());
        CognitiveWorkerRuntime.Thought add = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeAdd);
        assertEquals("[\"add\",\"" + path + "\"]", add.inputs().get("argsJson"));

        CognitiveWorkerRuntime.Cycle added = successfulCycle(5, "workspace.git.run", add.inputs());
        CognitiveWorkerRuntime.CognitiveContext beforeCommit = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.read", "workspace.file.write",
                        "workspace.test.run", "workspace.git.run", "workspace.git.status",
                        "workspace.github.pr.publish"),
                List.of(materialized, read, written, tested, added), Map.of());
        CognitiveWorkerRuntime.Thought commit = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeCommit);
        assertTrue(commit.inputs().get("argsJson").startsWith("[\"commit\",\"-m\","));

        CognitiveWorkerRuntime.Cycle committed = successfulCycle(6, "workspace.git.run", commit.inputs());
        CognitiveWorkerRuntime.CognitiveContext beforeStatus = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.read", "workspace.file.write",
                        "workspace.test.run", "workspace.git.run", "workspace.git.status",
                        "workspace.github.pr.publish"),
                List.of(materialized, read, written, tested, added, committed), Map.of());
        assertNull(GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeStatus),
                "independent Observation verification must not invent a redundant Git-status action");

        CognitiveWorkerRuntime.CognitiveContext readyToPublish = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.read", "workspace.file.write",
                        "workspace.test.run", "workspace.git.run", "workspace.git.status",
                        "workspace.github.pr.publish"),
                List.of(materialized, read, written, tested, added, committed), Map.of());
        CognitiveWorkerRuntime.Thought publish =
                GeneralCognitiveWorkerBrain.governedRemoteProposalPrecondition(readyToPublish);
        assertEquals("workspace.github.pr.publish", publish.actionRef());
    }

    @Test
    void exactReplacementFailsClosedWhenRequestedOldTextIsNotUnique() {
        ExecutionWorkSpec work = exactReplacementAndPublishWork();
        String path = "src/main/java/com/metatron/workforce/action/GeneralCognitiveWorkerBrainFactory.java";
        String oldText = "Produces one stateful provider-backed brain per Cognitive Worker execution.";
        CognitiveWorkerRuntime.Cycle materialized = successfulCycle(
                1, "workspace.repository.materialize",
                Map.of("repository", "kelvinka38/metatron-workforce", "ref", SHA));
        CognitiveWorkerRuntime.Cycle read = new CognitiveWorkerRuntime.Cycle(
                2,
                new CognitiveWorkerRuntime.Thought("workspace.file.read", Map.of("path", path), "read"),
                ActionFabric.ActionObservation.success(
                        "workspace.file.read", "read",
                        Map.of("path", path, "content", oldText + "\n" + oldText + "\n"),
                        List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("replace"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.file.read", "workspace.file.write"),
                List.of(materialized, read), Map.of());

        assertThrows(IllegalStateException.class,
                () -> GeneralCognitiveWorkerBrain.governedExactTextReplacementPrecondition(context));
    }

    private static ExecutionWorkSpec iterativeRepairWork() {
        return new ExecutionWorkSpec(
                "repair-unknown-defect",
                "Inspect an unfamiliar defect, repair the source, run tests, diagnose failures, and keep iterating until tests pass",
                "kelvinka38/example",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("defect repaired", "tests pass after the latest source mutation"),
                List.of("governed workspace.test.run evidence"));
    }

    private static ExecutionWorkSpec exactReplacementAndPublishWork() {
        return new ExecutionWorkSpec(
                "repair-and-publish",
                "Take ownership of one governed MUTATING general engineering Objective against "
                        + "kelvinka38/metatron-workforce at exact source commit " + SHA
                        + " using execution.general.workspace. Materialize that exact repository snapshot into the Objective workspace. "
                        + "Read src/main/java/com/metatron/workforce/action/GeneralCognitiveWorkerBrainFactory.java and replace exactly one "
                        + "Javadoc sentence 'Produces one stateful provider-backed brain per Cognitive Worker execution.' with "
                        + "'Produces one stateful provider-backed brain for each governed Cognitive Worker execution.' "
                        + "Do not modify any other source path. Run the full repository test suite through the governed test action after "
                        + "the edit and require PASS. Stage the intended source change and create one local Git commit. Publish the clean "
                        + "committed Objective workspace through the governed GitHub proposal action as an Objective-scoped reviewable "
                        + "unmerged pull request against main. Verify both tests and the remote pull request through independent Observation. "
                        + "Do not merge.",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("requested workspace source change is present in the committed work product",
                        "repository tests pass after the requested workspace change",
                        "reviewable pull request exists for the committed Objective work product",
                        "remote proposal remains unmerged"),
                List.of("general-action-runtime:execution.general.workspace",
                        "requested-capability:execution.general.workspace",
                        "general Action Fabric action journal",
                        "governed test action evidence",
                        "fresh authoritative GitHub API Observation"));
    }

    private static CognitiveWorkerRuntime.Cycle successfulCycle(int number, String actionRef, Map<String, String> inputs) {
        return new CognitiveWorkerRuntime.Cycle(
                number,
                new CognitiveWorkerRuntime.Thought(actionRef, inputs, "required action"),
                ActionFabric.ActionObservation.success(actionRef, "success", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("continue"));
    }

    private static ExecutionWorkSpec exactSnapshotAndTestWork() {
        return new ExecutionWorkSpec(
                "checkout_and_test",
                "Materialize repository snapshot at specific commit " + SHA + " and run the test suite",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Workspace contains source tree for commit " + SHA + " and tests pass"),
                List.of("governed workspace.test.run evidence"));
    }

    private static ExecutionWorkSpec stageAndCommitWork() {
        return new ExecutionWorkSpec(
                "stage_and_commit",
                "Stage only docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md and commit the proof file locally",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of("run_tests"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("One local git commit exists containing exactly the proof file"),
                List.of("Git show output verifying commit message and staged diff"));
    }

    private static ExecutionWorkSpec exactSnapshotWork() {
        return new ExecutionWorkSpec(
                "checkout_target_snapshot",
                "Materialize repository snapshot at specific commit " + SHA,
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Workspace contains source tree for commit " + SHA),
                List.of("git rev-parse HEAD matches " + SHA));
    }
}
