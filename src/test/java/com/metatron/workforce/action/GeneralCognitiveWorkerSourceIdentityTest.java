package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneralCognitiveWorkerSourceIdentityTest {
    private static final String SHA = "14d4d389b7487cf6d065f77b78217f0cfa73bb96";
    private static final String BASELINE = "6e6b837f0ac3b11923995dbde110c77a85d3426a";

    @Test
    void exactMaterializationUsesSourceProvenanceInsteadOfLocalBaselineHeadForSourceIdentity() {
        ExecutionWorkSpec work = exactMaterializationWork();
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.git.status"), List.of(), Map.of());
        ActionFabric.ActionObservation observation = new ActionFabric.ActionObservation(
                "workspace.repository.materialize", true, "repository materialized",
                Map.of(
                        "repository", "kelvinka38/metatron-workforce",
                        "sourceCommitSha", SHA,
                        "localBaselineCommitSha", BASELINE,
                        "materializedFiles", "321",
                        "workspaceRef", "execution-workspace:0123456789abcdef0123456789abcdef:component:primary"),
                List.of("repository-materialized:kelvinka38/metatron-workforce@" + SHA), Instant.now());

        CognitiveWorkerRuntime.Reflection reflection =
                GeneralCognitiveWorkerBrain.exactSourceMaterializationReflection(context, observation);

        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, reflection.decision());
    }

    @Test
    void materializationWithWrongSourceCommitFailsCanonicalIdentityVerification() {
        ExecutionWorkSpec work = exactMaterializationWork();
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize"), List.of(), Map.of());
        ActionFabric.ActionObservation observation = new ActionFabric.ActionObservation(
                "workspace.repository.materialize", true, "repository materialized",
                Map.of(
                        "repository", "kelvinka38/metatron-workforce",
                        "sourceCommitSha", "1111111111111111111111111111111111111111",
                        "localBaselineCommitSha", BASELINE,
                        "materializedFiles", "321",
                        "workspaceRef", "execution-workspace:0123456789abcdef0123456789abcdef:component:primary"),
                List.of(), Instant.now());

        CognitiveWorkerRuntime.Reflection reflection =
                GeneralCognitiveWorkerBrain.exactSourceMaterializationReflection(context, observation);

        assertEquals(CognitiveWorkerRuntime.Decision.FAILED, reflection.decision());
    }

    @Test
    void exactMaterializationCannotCompleteWhenTheWorkAlsoRequiresTests() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step_1",
                "Materialize snapshot at commit " + SHA + " and run the test suite",
                "kelvinka38/metatron-workforce@" + SHA,
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Repository snapshot is accessible and tests pass"),
                List.of("workspace.test.run success"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.test.run"), List.of(), Map.of());
        ActionFabric.ActionObservation observation = new ActionFabric.ActionObservation(
                "workspace.repository.materialize", true, "repository materialized",
                Map.of(
                        "repository", "kelvinka38/metatron-workforce",
                        "sourceCommitSha", SHA,
                        "localBaselineCommitSha", BASELINE,
                        "materializedFiles", "321",
                        "workspaceRef", "execution-workspace:0123456789abcdef0123456789abcdef:component:primary"),
                List.of(), Instant.now());

        CognitiveWorkerRuntime.Reflection reflection =
                GeneralCognitiveWorkerBrain.exactSourceMaterializationReflection(context, observation);

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, reflection.decision());
    }

    private static ExecutionWorkSpec exactMaterializationWork() {
        return new ExecutionWorkSpec(
                "step_1",
                "Materialize snapshot of repository kelvinka38/metatron-workforce at commit " + SHA + " in a local workspace",
                "kelvinka38/metatron-workforce@" + SHA,
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Repository snapshot at commit " + SHA + " is fully checked out and accessible"),
                List.of("Workspace directory listing and HEAD commit hash matching " + SHA,
                        "general-action-runtime:execution.general.workspace"));
    }
}
