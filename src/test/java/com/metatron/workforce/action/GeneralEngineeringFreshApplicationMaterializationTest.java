package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident (2026-09-17): the exact new-app General Engineering Objective deterministically
 * livelocked. GeneralCognitiveWorkerBrain.requiresRepositoryMaterialization() treated ANY parsable
 * repository-shaped target() as proof an existing GitHub source repository must be checked out, but
 * after #465 target() is always repository-shaped -- including for a brand-new application that has no
 * existing source yet. Every one of 48 cycles forced the identical workspace.repository.materialize
 * action, which failed identically with GitHub 404 every time, because
 * repositoryMaterializationPrecondition() never checked whether the same action had already failed.
 *
 * These tests prove: (1) [superseded, see below] (2) a genuinely existing-repository Objective is
 * unaffected and still requires real materialization; (3) a required materialization that genuinely
 * fails terminates truthfully after two cycles instead of burning the entire cognitive cycle budget.
 *
 * <p>Root-cause fix (2026-09-23, Founder-reported): claim (1) above is superseded. Never materializing a
 * fresh-new-application's derived destination meant {@code .metatron-repository} provenance -- the one
 * thing {@code GitHubWorkspaceProposalPublisher.publish()} requires to open a reviewable PR -- could
 * never be established for exactly this kind of Work, so a completed fresh-app Objective had no possible
 * path to Human-visible output ("if it can not work end to end, it still stupid"). Materialization is now
 * required for fresh-new-application work too, with a new {@code createIfMissing=true} input the brain
 * sets only for this planner-derived-destination case (never for an Objective-named existing repository,
 * which must keep failing closed on a typo): see {@code RepositoryWorkspaceMaterializationService
 * .materialize(..., createIfMissing)} and {@code GeneralWorkspacePhasePlanner}'s broadened publish
 * requirement. The anti-livelock backoff this incident fix introduced (claim 3) is untouched and still
 * applies identically to a fresh-app materialization that genuinely fails.</p>
 */
class GeneralEngineeringFreshApplicationMaterializationTest {
    private static final String SHA = "3e86d4e2876a90c580383d5c1de48360b5049b3b";

    @Test
    void freshNewApplicationWorkMaterializesADeterministicallyCreatedDestinationInsteadOfSkippingIt() {
        // Superseded (see class javadoc): a brand-new application's derived repository target now
        // materializes with createIfMissing=true, so a real destination gets created rather than
        // permanently blocking DELIVER's later GitHub publish for lack of provenance.
        ExecutionWorkSpec work = freshNewApplicationWork();
        CognitiveWorkerRuntime.CognitiveContext freshContext = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write", "workspace.build.run",
                        "workspace.test.run", "workspace.process.run", "workspace.git.run"),
                List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(freshContext);

        assertEquals("workspace.repository.materialize", thought.actionRef());
        assertEquals("kelvinka38/metatron-workforce-control-center", thought.inputs().get("repository"));
        assertEquals("true", thought.inputs().get("createIfMissing"));
    }

    @Test
    void freshApplicationProviderMustCreateSourceBeforeAnyGitActionIsVisible() {
        ExecutionWorkSpec work = freshNewApplicationWork();
        List<String> catalog = List.of(
                "workspace.file.write",
                "workspace.file.read",
                "workspace.dependencies.install",
                "workspace.build.run",
                "workspace.test.run",
                "workspace.process.run",
                "workspace.git.run",
                "workspace.git.status",
                "workspace.github.pr.publish");
        CognitiveWorkerRuntime.CognitiveContext empty = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                catalog, List.of(), Map.of());

        CognitiveWorkerRuntime.CognitiveContext bootstrap =
                GeneralCognitiveWorkerBrain.providerActionSelectionContext(empty);

        assertEquals(List.of("workspace.file.write"), bootstrap.availableActions(),
                "an empty fresh-app workspace must expose only source creation to provider cognition");

        CognitiveWorkerRuntime.Cycle wroteSource = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought(
                        "workspace.file.write",
                        Map.of("path", "index.html", "content", "<!doctype html><title>Control Center</title>"),
                        "create initial application source"),
                ActionFabric.ActionObservation.success(
                        "workspace.file.write", "written", Map.of(), List.of("workspace-write:index.html")),
                CognitiveWorkerRuntime.Reflection.continueWith("continue implementation"));
        CognitiveWorkerRuntime.CognitiveContext afterWrite = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                catalog, List.of(wroteSource), Map.of());

        List<String> providerActions = GeneralCognitiveWorkerBrain
                .providerActionSelectionContext(afterWrite)
                .availableActions();

        assertTrue(providerActions.contains("workspace.file.write"));
        assertTrue(providerActions.contains("workspace.file.read"));
        assertFalse(providerActions.contains("workspace.dependencies.install"),
                "dependency installation must stay hidden until a project manifest exists");
        assertFalse(providerActions.contains("workspace.build.run"));
        assertFalse(providerActions.contains("workspace.test.run"));
        assertFalse(providerActions.contains("workspace.process.run"));
        assertFalse(providerActions.contains("workspace.git.run"));

        CognitiveWorkerRuntime.Cycle wroteManifest = new CognitiveWorkerRuntime.Cycle(
                2,
                new CognitiveWorkerRuntime.Thought(
                        "workspace.file.write",
                        Map.of("path", "package.json",
                                "content", "{\"scripts\":{\"test\":\"node --test\"},\"dependencies\":{\"express\":\"latest\"}}"),
                        "define Node project and dependency manifest"),
                ActionFabric.ActionObservation.success(
                        "workspace.file.write", "written", Map.of(), List.of("workspace-write:package.json")),
                CognitiveWorkerRuntime.Reflection.continueWith("install and verify"));
        CognitiveWorkerRuntime.CognitiveContext afterManifest = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                catalog, List.of(wroteSource, wroteManifest), Map.of());

        List<String> lifecycleActions = GeneralCognitiveWorkerBrain
                .providerActionSelectionContext(afterManifest)
                .availableActions();

        assertTrue(lifecycleActions.contains("workspace.dependencies.install"));
        assertTrue(lifecycleActions.contains("workspace.build.run"));
        assertTrue(lifecycleActions.contains("workspace.test.run"));
        assertTrue(lifecycleActions.contains("workspace.process.run"));
        assertFalse(lifecycleActions.contains("workspace.git.run"),
                "Git staging/commit is a deterministic governed postcondition, not a provider-selected fresh-app action");
        assertFalse(lifecycleActions.contains("workspace.git.status"));
        assertFalse(lifecycleActions.contains("workspace.github.pr.publish"));
    }

    @Test
    void existingRepositoryProviderCatalogIsNotPhaseRestricted() {
        ExecutionWorkSpec existing = new ExecutionWorkSpec(
                "repair",
                "Repair a defect in the existing repository, run tests, commit the change",
                "repository:kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"),
                List.of("runtime evidence"));
        List<String> catalog = List.of("workspace.file.write", "workspace.git.run", "workspace.git.status");
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", existing, "idempotency",
                catalog, List.of(), Map.of());

        assertEquals(catalog, GeneralCognitiveWorkerBrain.providerActionSelectionContext(context).availableActions());
    }

    @Test
    void freshNewApplicationCompletionRequiresRealWorkspaceSourceMutationInsteadOfMaterialization() {
        ExecutionWorkSpec work = freshNewApplicationWork();
        CognitiveWorkerRuntime.CognitiveContext noSourceYet = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write"), List.of(), Map.of());

        CognitiveWorkerRuntime.Reflection guarded = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                noSourceYet,
                ActionFabric.ActionObservation.success("workspace.file.list", "listed", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, guarded.decision());
        assertTrue(guarded.summary().contains("workspace source/work-product for the new application"),
                "completion must be rejected until real source exists in the fresh Objective workspace");

        CognitiveWorkerRuntime.Cycle wroteSource = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought("workspace.file.write",
                        Map.of("path", "src/App.java", "content", "class App {}"), "create source"),
                ActionFabric.ActionObservation.success("workspace.file.write", "written", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("continue"));
        CognitiveWorkerRuntime.CognitiveContext sourceCreated = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write"),
                List.of(wroteSource), Map.of());

        // Superseded (see class javadoc): materialization is no longer skipped for fresh-app work, so it
        // still fires here since it has not yet succeeded in this context's history -- source having been
        // written does not substitute for it.
        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(sourceCreated);
        assertEquals("workspace.repository.materialize", thought.actionRef());
        assertEquals("true", thought.inputs().get("createIfMissing"));
    }

    @Test
    void explicitMaterializationIntentOverridesFreshApplicationMarkingAndStillRequiresRealSource() {
        // Fresh-app semantics must never suppress an EXPLICIT materialize/snapshot/exact-SHA request in
        // the same Work text: that always means a real, existing source baseline is required.
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                "Materialize repository snapshot at specific commit " + SHA + " before building the new application",
                "repository:kelvinka38/metatron-workforce-control-center",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("exact snapshot exists"),
                List.of("git rev-parse HEAD matches " + SHA,
                        "workspace-source:fresh-new-application"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(context);
        assertEquals("workspace.repository.materialize", thought.actionRef());
        assertEquals("kelvinka38/metatron-workforce-control-center", thought.inputs().get("repository"));
    }

    @Test
    void verifyPhaseTestPreconditionFiresWithoutMaterializationEvenThoughTheActionRemainsOffered() {
        // Root-cause fix (2026-09-22, discovered by a real end-to-end acceptance run): for ANY phased
        // Objective (not just a fresh application), materialization belongs exclusively to PRODUCE --
        // requiresRepositoryMaterialization() already returns false for every later phase. But
        // governedTestPrecondition() unconditionally required materializationSatisfied() regardless of
        // whether materialization was ever actually required, and materializationSatisfied() itself
        // returns false whenever workspace.repository.materialize is merely offered in the catalog and
        // has not been successfully invoked -- true for every phased VERIFY, since the action stays in
        // the general-workspace catalog throughout. The precondition therefore permanently returned null
        // for VERIFY's own governed test step, forcing cognition to reach the exact same decision on its
        // own every cycle with no deterministic backstop.
        ExecutionWorkSpec verify = new ExecutionWorkSpec(
                "general-engineering-workspace-execution-verify",
                "VERIFY PHASE. Verify the carried workspace from the completed production phase.",
                "repository:kelvinka38/metatron-workforce-control-center",
                "execution.general.workspace",
                List.of("general-engineering-workspace-execution-prepare"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("governed build succeeds", "governed tests succeed"),
                List.of(GeneralWorkspacePhasePlanner.PHASE_VERIFY, GeneralWorkspacePhasePlanner.REQUIRE_BUILD,
                        GeneralWorkspacePhasePlanner.REQUIRE_TEST, "workspace-source:fresh-new-application"));
        CognitiveWorkerRuntime.Cycle buildSucceeded = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought("workspace.build.run", Map.of(), "build"),
                ActionFabric.ActionObservation.success("workspace.build.run", "build ok", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("continue to test"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", verify, "idempotency",
                List.of("workspace.repository.materialize", "workspace.build.run", "workspace.test.run",
                        "workspace.process.run"),
                List.of(buildSucceeded), Map.of());

        CognitiveWorkerRuntime.Thought thought = GeneralCognitiveWorkerBrain.governedTestPrecondition(context);

        assertEquals("workspace.test.run", thought.actionRef(),
                "governedTestPrecondition must deterministically fire once build has succeeded, even though "
                        + "workspace.repository.materialize is still offered and was never invoked -- "
                        + "materialization is never required outside PRODUCE for a phased Objective");
    }

    @Test
    void existingRepositoryWorkStillRequiresRealMaterializationRegressionForIssue465() {
        // #465 regression guard: ordinary existing-repository General Engineering work (no fresh-app
        // sentinel) must still force real materialization exactly as before this fix.
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "repair",
                "Repair a defect in the existing repository, run tests, commit the change",
                "repository:kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"), List.of("runtime evidence"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize", "workspace.file.write"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(context);
        assertEquals("workspace.repository.materialize", thought.actionRef());
        assertEquals("kelvinka38/metatron-workforce", thought.inputs().get("repository"));
    }

    @Test
    void requiredMaterializationPreconditionDoesNotBlindlyRepeatAnIdenticallyFailedAction() {
        ExecutionWorkSpec work = exactSnapshotWork();
        CognitiveWorkerRuntime.CognitiveContext fresh = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought forced =
                GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(fresh);
        assertEquals("workspace.repository.materialize", forced.actionRef());

        CognitiveWorkerRuntime.Cycle failedOnce = new CognitiveWorkerRuntime.Cycle(
                1,
                forced,
                ActionFabric.ActionObservation.failure(
                        "workspace.repository.materialize", "repository commit resolution HTTP 404",
                        List.of("action-exception:IllegalStateException")),
                CognitiveWorkerRuntime.Reflection.continueWith("inspect the failure"));
        CognitiveWorkerRuntime.CognitiveContext afterOneFailure = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize"), List.of(failedOnce), Map.of());

        assertNull(GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(afterOneFailure),
                "the deterministic precondition must back off after the first identical failure instead of "
                        + "forcing the exact same action every remaining cycle");
    }

    @Test
    void anIdenticalSecondFailureTerminatesTruthfullyInsteadOfContinuingForever() {
        ExecutionWorkSpec work = exactSnapshotWork();
        CognitiveWorkerRuntime.Cycle failedOnce = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought("workspace.repository.materialize",
                        Map.of("repository", "kelvinka38/metatron-workforce", "ref", SHA), "materialize"),
                ActionFabric.ActionObservation.failure(
                        "workspace.repository.materialize", "repository commit resolution HTTP 404", List.of()),
                CognitiveWorkerRuntime.Reflection.continueWith("inspect the failure"));
        CognitiveWorkerRuntime.CognitiveContext afterOneFailure = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                List.of("workspace.repository.materialize"), List.of(failedOnce), Map.of());
        ActionFabric.ActionObservation secondFailure = ActionFabric.ActionObservation.failure(
                "workspace.repository.materialize", "repository commit resolution HTTP 404", List.of());

        CognitiveWorkerRuntime.Reflection reflection =
                GeneralCognitiveWorkerBrain.deterministicNonTerminalReflection(afterOneFailure, secondFailure);

        assertEquals(CognitiveWorkerRuntime.Decision.FAILED, reflection.decision());
        assertTrue(reflection.summary().contains("bounded recovery is not possible"));
    }

    /**
     * Full-loop reproduction of the exact production livelock, using the real
     * GeneralCognitiveWorkerBrain (not a scripted test double) driven through the real
     * CognitiveWorkerRuntime.execute() loop against a fabric action that always fails exactly like the
     * production 404. The cognitive cycle budget is deliberately configured far above 2 (matching
     * production's 48) so a regression that reintroduces the livelock is caught by the assertion on
     * action count, not merely by chance early termination.
     */
    @Test
    void fullCognitiveLoopTerminatesAfterTwoCyclesInsteadOfExhaustingTheCognitiveCycleBudget() {
        AtomicInteger materializeAttempts = new AtomicInteger();
        ActionFabric fabric = new ActionFabric(List.of(new ActionFabric.Action() {
            @Override public String actionRef() { return "workspace.repository.materialize"; }
            @Override public ActionFabric.Consequence consequence() { return ActionFabric.Consequence.READ_ONLY; }
            @Override public Set<String> allowedWorkers() { return Set.of("worker"); }
            @Override public Set<String> acceptedAuthorizations() { return Set.of("authorization"); }
            @Override public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
                materializeAttempts.incrementAndGet();
                throw new IllegalStateException("repository commit resolution HTTP 404");
            }
        }));
        AtomicInteger intelligenceCalls = new AtomicInteger();
        WorkerIntelligenceService retryingProvider = request -> {
            int call = intelligenceCalls.incrementAndGet();
            // Cognition retries the same required action once more despite the "do not blindly repeat"
            // guidance in the system prompt -- exactly the unconstrained behavior that produced 48
            // identical cycles in production. The anti-livelock reflection backstop, not cognition's own
            // discipline, must be what actually stops this.
            return new WorkerIntelligenceService.Response(
                    "intelligence-retry:" + call,
                    "{\"actionRef\":\"workspace.repository.materialize\",\"inputs\":{\"repository\":\"kelvinka38/metatron-workforce\",\"ref\":\""
                            + SHA + "\"},\"rationale\":\"retry despite prior failure\"}",
                    List.of("intelligence-provider:test"));
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(retryingProvider, new ObjectMapper());
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, ActionJournal.noop(), 48);
        ExecutionWorkSpec work = exactSnapshotWork();

        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                "worker", "assignment", "authorization", "objective", work, "idempotency", brain);

        assertFalse(outcome.success());
        assertEquals(2, outcome.cycles().size(),
                "the exact production incident burned 48 identical cycles; the fix must terminate after "
                        + "the second (first repeated) identical failure");
        assertEquals(2, materializeAttempts.get());
        assertEquals(CognitiveWorkerRuntime.Decision.FAILED, outcome.cycles().getLast().reflection().decision());
        assertTrue(outcome.summary().contains("bounded recovery is not possible"));
        assertFalse(outcome.evidenceReferences().stream().anyMatch(ref -> ref.contains("cognitive-cycle-budget-exhausted")),
                "the loop must fail closed on the repeated action, never merely exhaust the cycle budget");
    }

    private static ExecutionWorkSpec freshNewApplicationWork() {
        return new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                "Take ownership of one governed Objective: build and deliver a complete runnable web "
                        + "application called Metatron Workforce Control Center. Assign the implementation "
                        + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                        + "tests, runtime verification, Git evidence, and terminal completion.",
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
