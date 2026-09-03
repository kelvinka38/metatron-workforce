package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeneralCognitiveWorkerBrainPreconditionTest {
    private static final String SHA = "3e86d4e2876a90c580383d5c1de48360b5049b3b";

    @Test
    void exactRepositorySnapshotMustMaterializeBeforeProviderSelectsGitActions() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "checkout_target_snapshot",
                "Materialize repository snapshot at specific commit " + SHA,
                "kelvinka38/metatron-workforce",
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
    void mutatingFollowUpStepDoesNotRematerializeAndDestroyPriorWorkspaceWork() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "create_proof",
                "Create proof file after repository materialization",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of("checkout_target_snapshot"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("proof exists"), List.of("file evidence"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write"), List.of(), Map.of());

        assertNull(GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(context));
    }
}
