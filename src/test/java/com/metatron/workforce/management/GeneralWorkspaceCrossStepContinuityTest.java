package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.CognitiveWorkerRuntime;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspaceCrossStepContinuityTest {
    private static final String SHA = "590aeeff27718784b9cbea7c485d2ea425cc5e4c";
    private static final String BASELINE_SHA = "4a1d7f6b83cc201ec3a192c90e2d146f1872bd45";

    @TempDir
    Path tempDir;

    @Test
    void priorMaterializationSeedsFreshFollowUpStepAndRemovesRedundantSetupAction() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(tempDir.resolve("workspaces"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        workspaces.write(workspace, "README.md", "source");
        workspaces.write(workspace, ".metatron-repository",
                "repository=kelvinka38/metatron-workforce\nrequestedRef=" + SHA + "\ncommitSha=" + SHA + "\n");
        workspaces.write(workspace, RepositoryWorkspaceMaterializationState.BASELINE_REF_PATH,
                BASELINE_SHA + "\n");

        Map<String, String> memory = GeneralWorkspaceAutonomousCapability.objectiveWorkspaceMemory(workspaces, workspace);
        assertEquals("true", memory.get(GeneralWorkspaceAutonomousCapability.MEMORY_WORKSPACE_MATERIALIZED));
        assertEquals("kelvinka38/metatron-workforce", memory.get("repository"));
        assertEquals(SHA, memory.get("sourceCommitSha"));
        assertEquals(BASELINE_SHA, memory.get("localBaselineCommitSha"));
        assertEquals(workspace.workspaceRef(), memory.get("workspaceRef"));

        ExecutionWorkSpec followUp = new ExecutionWorkSpec(
                "step-2",
                "Create proof file docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md containing the exact source commit SHA within the local workspace.",
                "kelvinka38/metatron-workforce",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of("step-1"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("proof file exists"),
                List.of("workspace diff"));
        List<ActionFabric.Action> filtered = GeneralWorkspaceAutonomousCapability.actionsForWork(
                List.of(action("workspace.repository.materialize"), action("workspace.file.write")), followUp, memory);
        List<String> refs = filtered.stream().map(ActionFabric.Action::actionRef).toList();
        assertFalse(refs.contains("workspace.repository.materialize"));
        assertTrue(refs.contains("workspace.file.write"));

        CognitiveWorkerRuntime.Brain delegate = new CognitiveWorkerRuntime.Brain() {
            @Override
            public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                assertTrue(context.history().isEmpty(), "new Work step must start with fresh step-local history");
                assertEquals("true", context.memory().get(GeneralWorkspaceAutonomousCapability.MEMORY_WORKSPACE_MATERIALIZED));
                assertEquals(SHA, context.memory().get("sourceCommitSha"));
                assertEquals("kelvinka38/metatron-workforce", context.memory().get("repository"));
                assertFalse(context.availableActions().contains("workspace.repository.materialize"));
                return new CognitiveWorkerRuntime.Thought("workspace.file.write", Map.of("path", "proof.md"), "continue execution");
            }

            @Override
            public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                              ActionFabric.ActionObservation observation) {
                return CognitiveWorkerRuntime.Reflection.complete("done");
            }
        };
        CognitiveWorkerRuntime.Brain contextual =
                GeneralWorkspaceAutonomousCapability.withObjectiveWorkspaceMemory(delegate, memory);
        CognitiveWorkerRuntime.CognitiveContext freshStep = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", followUp, "idempotency",
                refs, List.of(), Map.of());

        assertEquals("workspace.file.write", contextual.think(freshStep).actionRef());
    }

    @Test
    void freshNewApplicationRemovesMaterializeActionBeforeFirstCognitionTurn() {
        ExecutionWorkSpec fresh = new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                "Build and deliver a complete runnable web application called Metatron Workforce Control Center",
                "repository:kelvinka38/metatron-workforce-control-center",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("runnable application is delivered"),
                List.of("workspace-source:fresh-new-application", "build/test/runtime evidence"));

        List<ActionFabric.Action> filtered = GeneralWorkspaceAutonomousCapability.actionsForWork(
                List.of(action("workspace.repository.materialize"), action("workspace.file.write"),
                        action("workspace.build.run"), action("workspace.test.run"),
                        action("workspace.process.run"), action("workspace.git.run")),
                fresh, Map.of(GeneralWorkspaceAutonomousCapability.MEMORY_WORKSPACE_MATERIALIZED, "false"));

        List<String> refs = filtered.stream().map(ActionFabric.Action::actionRef).toList();
        assertFalse(refs.contains("workspace.repository.materialize"),
                "a destination repository for a fresh application must not be exposed as a source-checkout action");
        assertTrue(refs.contains("workspace.file.write"));
        assertTrue(refs.contains("workspace.build.run"));
        assertTrue(refs.contains("workspace.test.run"));
        assertTrue(refs.contains("workspace.process.run"));
        assertTrue(refs.contains("workspace.git.run"));
    }

    @Test
    void explicitMaterializationStepRetainsMaterializeActionEvenWhenWorkspaceAlreadyExists() {
        ExecutionWorkSpec materialize = new ExecutionWorkSpec(
                "step-1",
                "Materialize repository snapshot kelvinka38/metatron-workforce at commit " + SHA,
                "kelvinka38/metatron-workforce",
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("exact snapshot exists"),
                List.of("source provenance"));
        Map<String, String> memory = Map.of(
                GeneralWorkspaceAutonomousCapability.MEMORY_WORKSPACE_MATERIALIZED, "true",
                "repository", "kelvinka38/metatron-workforce",
                "sourceCommitSha", SHA);
        List<ActionFabric.Action> filtered = GeneralWorkspaceAutonomousCapability.actionsForWork(
                List.of(action("workspace.repository.materialize"), action("workspace.file.read")), materialize, memory);

        assertTrue(filtered.stream().map(ActionFabric.Action::actionRef).toList().contains("workspace.repository.materialize"));
    }

    private static ActionFabric.Action action(String ref) {
        return new ActionFabric.Action() {
            @Override public String actionRef() { return ref; }
            @Override public ActionFabric.Consequence consequence() { return ActionFabric.Consequence.READ_ONLY; }
            @Override public Set<String> allowedWorkers() { return Set.of("worker"); }
            @Override public Set<String> acceptedAuthorizations() { return Set.of("authorization"); }
            @Override public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
                return ActionFabric.ActionObservation.success(ref, "ok", Map.of(), List.of());
            }
        };
    }
}
