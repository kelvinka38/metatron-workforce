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
    void explicitExactShaFileWriteExecutesAndReflectsWithoutProvider() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-file-write",
                "Create or replace only docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md with a short proof containing exact source SHA "
                        + SHA + ". Exact UTF-8 content: source_sha=" + SHA,
                "docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md",
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
    void stageAndCommitWorkForcesBothGovernedGitSubactionsInOrder() {
        ExecutionWorkSpec work = stageAndCommitWork();
        CognitiveWorkerRuntime.CognitiveContext beforeAdd = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought add = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeAdd);

        assertEquals("workspace.git.run", add.actionRef());
        assertEquals("[\"add\",\"docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md\"]",
                add.inputs().get("argsJson"));

        CognitiveWorkerRuntime.Cycle added = successfulCycle(1, "workspace.git.run", add.inputs());
        CognitiveWorkerRuntime.CognitiveContext beforeCommit = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.git.run", "workspace.git.status"), List.of(added), Map.of());

        CognitiveWorkerRuntime.Thought commit = GeneralCognitiveWorkerBrain.governedGitPrecondition(beforeCommit);

        assertEquals("workspace.git.run", commit.actionRef());
        assertEquals(true, commit.inputs().get("argsJson").startsWith("[\"commit\",\"-m\","));

        CognitiveWorkerRuntime.Cycle committed = successfulCycle(2, "workspace.git.run", commit.inputs());
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
                "Stage and commit the proof file locally",
                "docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md",
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
