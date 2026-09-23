package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact production Objective (2026-09-17): "Take ownership of one governed Objective: build and deliver
 * a complete runnable web application called Metatron Workforce Control Center. Assign the
 * implementation to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, tests,
 * runtime verification, Git evidence, and terminal completion." explicitly requires six phases: (1)
 * coding/actual source creation, (2) build, (3) tests, (4) runtime verification, (5) Git evidence, (6)
 * terminal completion. This proves the completion floor rejects COMPLETE while any one of those
 * durable-evidence categories is missing, and only accepts COMPLETE once all of them are observed --
 * never from a fabricated/text-only claim.
 */
class GeneralEngineeringExactObjectiveCompletionFloorTest {
    private static final String OBJECTIVE_TEXT =
            "Take ownership of one governed Objective: build and deliver a complete runnable web "
                    + "application called Metatron Workforce Control Center. Assign the implementation "
                    + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                    + "tests, runtime verification, Git evidence, and terminal completion.";

    @Test
    void completeIsRejectedWhileSourceWorkProductIsMissing() {
        CognitiveWorkerRuntime.CognitiveContext context = contextWithHistory(List.of());

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.file.list", "listed", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
        assertEquals(true, guarded.summary().contains("workspace source/work-product for the new application"));
    }

    @Test
    void completeIsRejectedWhileBuildEvidenceIsMissing() {
        List<CognitiveWorkerRuntime.Cycle> history = List.of(sourceWritten());
        CognitiveWorkerRuntime.CognitiveContext context = contextWithHistory(history);

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.file.list", "listed", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
        assertEquals(true, guarded.summary().contains("successful workspace.build.run"));
    }

    @Test
    void completeIsRejectedWhileTestEvidenceIsMissing() {
        List<CognitiveWorkerRuntime.Cycle> history = List.of(sourceWritten(), buildSucceeded());
        CognitiveWorkerRuntime.CognitiveContext context = contextWithHistory(history);

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.file.list", "listed", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
        assertEquals(true, guarded.summary().contains("successful workspace.test.run"));
    }

    @Test
    void completeIsRejectedWhileRuntimeVerificationEvidenceIsMissing() {
        List<CognitiveWorkerRuntime.Cycle> history = List.of(sourceWritten(), buildSucceeded(), testsPassed());
        CognitiveWorkerRuntime.CognitiveContext context = contextWithHistory(history);

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.file.list", "listed", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
        assertEquals(true, guarded.summary().contains("successful workspace.process.run runtime verification"));
    }

    @Test
    void completeIsRejectedWhileGitEvidenceIsMissing() {
        List<CognitiveWorkerRuntime.Cycle> history =
                List.of(sourceWritten(), buildSucceeded(), testsPassed(), runtimeVerified());
        CognitiveWorkerRuntime.CognitiveContext context = contextWithHistory(history);

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.file.list", "listed", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
        assertEquals(true, guarded.summary().contains("workspace.git.run"));
    }

    @Test
    void completeIsAcceptedOnlyOnceEveryRequestedPhaseIsObservedWithDurableEvidence() {
        // workspace.process.run (runtime verification) itself counts as a possible workspace mutation for
        // governed-test freshness purposes (see GeneralCognitiveWorkerBrain.governedTestSatisfied), so the
        // governed test must be the most recent such action observed -- exactly as it already must be
        // after any other mutation. Ordering runtime verification before the final test still proves every
        // one of the six requested phases (source, build, test, runtime, Git) is present.
        //
        // Root-cause fix (2026-09-23, Founder-reported): this flat fresh-new-application Work now also
        // requires a successful workspace.repository.materialize (createIfMissing=true) before completion,
        // exactly like a phased fresh-app Objective's PRODUCE phase -- otherwise DELIVER's later GitHub
        // publish would have no provenance and completed work would have no path to Human-visible output.
        List<CognitiveWorkerRuntime.Cycle> history = List.of(
                materialized(), sourceWritten(), buildSucceeded(), runtimeVerified(), testsPassed(), gitAdded(), gitCommitted());
        CognitiveWorkerRuntime.CognitiveContext context = contextWithHistory(history);

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context,
                ActionFabric.ActionObservation.success("workspace.git.run", "committed", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("all required evidence observed"));

        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, guarded.decision());
    }

    private static CognitiveWorkerRuntime.CognitiveContext contextWithHistory(List<CognitiveWorkerRuntime.Cycle> history) {
        return new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", exactProductionWork(), "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write", "workspace.build.run",
                        "workspace.test.run", "workspace.process.run", "workspace.git.run", "workspace.git.status"),
                history, Map.of());
    }

    private static CognitiveWorkerRuntime.Cycle materialized() {
        return successfulCycle(1, "workspace.repository.materialize",
                Map.of("repository", "kelvinka38/metatron-workforce-control-center", "createIfMissing", "true"));
    }

    private static CognitiveWorkerRuntime.Cycle sourceWritten() {
        return successfulCycle(1, "workspace.file.write", Map.of("path", "src/App.java"));
    }

    private static CognitiveWorkerRuntime.Cycle buildSucceeded() {
        return successfulCycle(2, "workspace.build.run", Map.of());
    }

    private static CognitiveWorkerRuntime.Cycle testsPassed() {
        return successfulCycle(3, "workspace.test.run", Map.of());
    }

    private static CognitiveWorkerRuntime.Cycle runtimeVerified() {
        return successfulCycle(4, "workspace.process.run", Map.of());
    }

    private static CognitiveWorkerRuntime.Cycle gitAdded() {
        return successfulCycle(5, "workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]"));
    }

    private static CognitiveWorkerRuntime.Cycle gitCommitted() {
        return successfulCycle(6, "workspace.git.run",
                Map.of("argsJson", "[\"commit\",\"-m\",\"Complete governed Objective work step\"]"));
    }

    private static CognitiveWorkerRuntime.Cycle successfulCycle(int number, String actionRef, Map<String, String> inputs) {
        return new CognitiveWorkerRuntime.Cycle(
                number,
                new CognitiveWorkerRuntime.Thought(actionRef, inputs, "required action"),
                ActionFabric.ActionObservation.success(actionRef, "success", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("continue"));
    }

    private static ExecutionWorkSpec exactProductionWork() {
        return new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                OBJECTIVE_TEXT,
                "repository:kelvinka38/metatron-workforce-control-center",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("canonical Worker WORKER-GENERAL-ENGINEERING performs the requested workspace execution",
                        "the work is executed under a real Workforce Assignment attributed to WORKER-GENERAL-ENGINEERING"
                                + " through its governed general workspace capability"),
                List.of("general-workspace-execution durable work product/evidence",
                        "worker-assignment evidence attributed to WORKER-GENERAL-ENGINEERING",
                        "workspace-source:fresh-new-application"));
    }
}
