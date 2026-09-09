package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
