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
