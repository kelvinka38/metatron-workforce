package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveWorkerFailureRecoveryTest {
    private static final String WORKER = "WORKER-RECOVERY-TEST";
    private static final String AUTH = "authorization:test:recovery";

    @Test
    void unrelatedSuccessCannotEraseFailedRequiredActionBeforeCompletion() {
        AtomicInteger requiredAttempts = new AtomicInteger();
        ActionFabric fabric = new ActionFabric(List.of(
                action("tool.required", request -> requiredAttempts.incrementAndGet() == 1
                        ? ActionFabric.ActionObservation.failure("tool.required", "transient failure", List.of("required-failed"))
                        : ActionFabric.ActionObservation.success("tool.required", "required effect recovered", Map.of(), List.of("required-recovered"))),
                action("tool.unrelated", request -> ActionFabric.ActionObservation.success(
                        "tool.unrelated", "unrelated observation", Map.of(), List.of("unrelated-success")))));
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, ActionJournal.noop(), 4);
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-recovery", "obtain required evidence", "fixture", "test.recovery", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("required effect succeeds"), List.of("required evidence"));

        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                WORKER, "assignment-recovery", AUTH, "objective-recovery", work, "recovery-key",
                new CognitiveWorkerRuntime.Brain() {
                    @Override
                    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                        return switch (context.history().size()) {
                            case 0 -> new CognitiveWorkerRuntime.Thought("tool.required", Map.of(), "run required action");
                            case 1 -> new CognitiveWorkerRuntime.Thought("tool.unrelated", Map.of(), "inspect unrelated state");
                            default -> new CognitiveWorkerRuntime.Thought("tool.required", Map.of(), "retry unresolved required action");
                        };
                    }

                    @Override
                    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                      ActionFabric.ActionObservation observation) {
                        return observation.success()
                                ? CognitiveWorkerRuntime.Reflection.complete("provider proposes completion")
                                : CognitiveWorkerRuntime.Reflection.continueWith("recover failed action");
                    }
                });

        assertTrue(outcome.success());
        assertEquals(3, outcome.cycles().size());
        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, outcome.cycles().get(1).reflection().decision());
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, outcome.cycles().get(2).reflection().decision());
        assertEquals(2, requiredAttempts.get());
        assertTrue(outcome.evidenceReferences().stream().anyMatch(ref ->
                ref.equals("cognitive-completion-rejected:unresolved-failed-actions=tool.required")));
    }

    @Test
    void domainBrainCanReleaseOptionalDiagnosticFailureWithoutWeakeningRequiredFailureDefault() {
        ActionFabric fabric = new ActionFabric(List.of(
                action("tool.diagnostic", request -> ActionFabric.ActionObservation.failure(
                        "tool.diagnostic", "diagnostic failed", List.of("diagnostic-failed"))),
                action("tool.required", request -> ActionFabric.ActionObservation.success(
                        "tool.required", "required evidence obtained", Map.of(), List.of("required-success")))));
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, ActionJournal.noop(), 3);
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-optional-diagnostic", "obtain required evidence", "fixture", "test.recovery", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("required effect succeeds"), List.of("required evidence"));

        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                WORKER, "assignment-optional", AUTH, "objective-optional", work, "optional-key",
                new CognitiveWorkerRuntime.Brain() {
                    @Override
                    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                        return context.history().isEmpty()
                                ? new CognitiveWorkerRuntime.Thought("tool.diagnostic", Map.of(), "optional diagnosis")
                                : new CognitiveWorkerRuntime.Thought("tool.required", Map.of(), "obtain required evidence");
                    }

                    @Override
                    public CognitiveWorkerRuntime.Reflection reflect(
                            CognitiveWorkerRuntime.CognitiveContext context,
                            ActionFabric.ActionObservation observation) {
                        return observation.success()
                                ? CognitiveWorkerRuntime.Reflection.complete("required acceptance evidence is satisfied")
                                : CognitiveWorkerRuntime.Reflection.continueWith("diagnostic was optional; continue with required path");
                    }

                    @Override
                    public boolean blocksCompletionForUnresolvedFailure(
                            CognitiveWorkerRuntime.CognitiveContext context,
                            String actionRef) {
                        return !"tool.diagnostic".equals(actionRef);
                    }
                });

        assertTrue(outcome.success());
        assertEquals(2, outcome.cycles().size());
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, outcome.cycles().get(1).reflection().decision());
        assertTrue(outcome.evidenceReferences().contains("diagnostic-failed"));
    }

    @Test
    void identicalFailingActionRepeatedTwiceCircuitBreaksInsteadOfBurningTheFullCycleBudget() {
        AtomicInteger invocations = new AtomicInteger();
        ActionFabric fabric = new ActionFabric(List.of(
                action("tool.flaky", request -> {
                    invocations.incrementAndGet();
                    return ActionFabric.ActionObservation.failure(
                            "tool.flaky", "deterministic-precondition-failure", List.of("flaky-failed"));
                })));
        int maxCycles = 20;
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, ActionJournal.noop(), maxCycles);
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-circuit-breaker", "repeat an identical failing action", "fixture", "test.recovery",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("required effect succeeds"), List.of("required evidence"));

        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                WORKER, "assignment-breaker", AUTH, "objective-breaker", work, "breaker-key",
                new CognitiveWorkerRuntime.Brain() {
                    @Override
                    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                        return new CognitiveWorkerRuntime.Thought(
                                "tool.flaky", Map.of(), "retry the same deterministic action every cycle");
                    }

                    @Override
                    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                      ActionFabric.ActionObservation observation) {
                        return CognitiveWorkerRuntime.Reflection.continueWith(
                                "keep retrying regardless of the identical failure");
                    }
                });

        assertTrue(outcome.success() == false, "a permanently failing deterministic action must not be reported as success");
        assertEquals(2, invocations.get(),
                "the action itself must only actually run twice before the breaker blocks a third identical attempt");
        assertEquals(3, outcome.cycles().size(),
                "cycle 3 must be the synthetic breaker-blocked cycle, not a real third invocation");
        assertTrue(maxCycles > outcome.cycles().size(),
                "the breaker must stop well short of the full cycle budget, not silently burn it to exhaustion");
        assertTrue(outcome.evidenceReferences().contains("action-repeated-failure-circuit-breaker:tool.flaky"));
        assertTrue(outcome.summary().contains("bounded retry exhausted"));
    }

    @Test
    void circuitBreakerSummarySurfacesTheRealSandboxFailureDetailNotJustAGenericMessage() {
        // Production incident (2026-09-22): workspace.dependencies.install kept reaching this exact
        // breaker, but the Objective's durable BLOCKED reason -- the only thing a Human or this session
        // could see without raw sandbox access -- was always the generic "failed identically twice"
        // message. The real npm/pip error text was captured in the sandboxed action's own
        // ActionObservation.outputs() but discarded before it ever reached that reason string, making
        // the actual root cause undiagnosable from anywhere durable.
        String realNpmError = "npm ERR! code E404\nnpm ERR! 404 Not Found - GET https://registry.npmjs.org/@metatron%2fmissing-package\nnpm ERR! 404 '@metatron/missing-package@^1.0.0' is not in this registry.";
        AtomicInteger invocations = new AtomicInteger();
        ActionFabric fabric = new ActionFabric(List.of(
                action("workspace.dependencies.install", request -> {
                    invocations.incrementAndGet();
                    return new ActionFabric.ActionObservation("workspace.dependencies.install", false,
                            "sandbox command failed",
                            Map.of("exitCode", "1", "output", realNpmError),
                            List.of("worker-sandbox:executable=npm:exit=1"), java.time.Instant.now());
                })));
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, ActionJournal.noop(), 20);
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-dependencies", "install dependencies", "fixture", "test.recovery",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("dependencies installed"), List.of("install evidence"));

        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                WORKER, "assignment-dependencies", AUTH, "objective-dependencies", work, "dependencies-key",
                new CognitiveWorkerRuntime.Brain() {
                    @Override
                    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                        return new CognitiveWorkerRuntime.Thought(
                                "workspace.dependencies.install", Map.of(), "retry dependency install");
                    }

                    @Override
                    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                      ActionFabric.ActionObservation observation) {
                        return CognitiveWorkerRuntime.Reflection.continueWith("keep retrying");
                    }
                });

        assertTrue(outcome.success() == false);
        assertEquals(2, invocations.get());
        assertTrue(outcome.summary().contains("npm ERR! 404"),
                "the real npm error must be visible in the durable failure summary -- a Human reading the "
                        + "BLOCKED reason must be able to see WHY it failed, not just THAT it failed twice");
    }

    @Test
    void circuitBreakerSummaryAlsoNamesTheEarlierDistinctFailureThatStartedTheLoop() {
        // Production 2026-09-24 (case-9371b421 VERIFY): the breaker reported only the repeated malformed
        // tasksJson; the earlier, different failure that sent cognition into that loop was never visible.
        ActionFabric fabric = new ActionFabric(List.of(
                action("workspace.dependencies.install", request -> new ActionFabric.ActionObservation(
                        "workspace.dependencies.install", false, "sandbox command failed",
                        Map.of("exitCode", "1", "output", "npm ERR! code ECONNREFUSED registry unreachable"),
                        List.of("worker-sandbox:executable=npm:exit=1"), java.time.Instant.now())),
                action("workspace.build.run", request -> new ActionFabric.ActionObservation(
                        "workspace.build.run", false, "invalid build input",
                        Map.of("error", "action-input-type:key=tasksJson"), List.of(), java.time.Instant.now()))));
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, ActionJournal.noop(), 20);
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-verify", "verify the application", "fixture", "test.recovery",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("build succeeds"), List.of("build evidence"));
        AtomicInteger cycles = new AtomicInteger();

        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                WORKER, "assignment-verify", AUTH, "objective-verify", work, "verify-key",
                new CognitiveWorkerRuntime.Brain() {
                    @Override
                    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                        return cycles.getAndIncrement() == 0
                                ? new CognitiveWorkerRuntime.Thought("workspace.dependencies.install", Map.of(), "install")
                                : new CognitiveWorkerRuntime.Thought("workspace.build.run",
                                        Map.of("tasksJson", "build"), "build");
                    }

                    @Override
                    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                      ActionFabric.ActionObservation observation) {
                        return CognitiveWorkerRuntime.Reflection.continueWith("keep going");
                    }
                });

        assertFalse(outcome.success());
        assertTrue(outcome.summary().contains("Earliest distinct failure in this step: cycle 1 workspace.dependencies.install"),
                outcome.summary());
        assertTrue(outcome.summary().contains("ECONNREFUSED"), outcome.summary());
    }

    private static ActionFabric.Action action(
            String ref,
            java.util.function.Function<ActionFabric.ActionRequest, ActionFabric.ActionObservation> invocation) {
        return new ActionFabric.Action() {
            @Override public String actionRef() { return ref; }
            @Override public ActionFabric.Consequence consequence() { return ActionFabric.Consequence.READ_ONLY; }
            @Override public Set<String> allowedWorkers() { return Set.of(WORKER); }
            @Override public Set<String> acceptedAuthorizations() { return Set.of(AUTH); }
            @Override public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) { return invocation.apply(request); }
        };
    }
}
