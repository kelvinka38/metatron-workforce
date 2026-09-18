package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.testing.GovernanceTestHarness;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Point4ActionFabricRuntimeConformanceTest {
    private static final String WORKER = "WORKER-COGNITIVE-TEST";
    private static final String AUTH = "authorization:test:cognitive";
    private static final String ASSIGNMENT = "assignment-1";
    private static final String OBJECTIVE = "objective-1";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T08:15:00Z"), ZoneOffset.UTC);

    @Test
    void workerThinksActsObservesReflectsAndChangesItsNextActionFromObservation() {
        List<String> invoked = new ArrayList<>();
        List<CognitiveWorkerRuntime.Cycle> journaled = new ArrayList<>();
        GovernanceTestHarness governance = new GovernanceTestHarness(CLOCK);
        ActionFabric fabric = new ActionFabric(List.of(
                action("tool.inspect", ActionFabric.Consequence.READ_ONLY, request -> {
                    invoked.add("inspect");
                    return new ActionFabric.ActionObservation("tool.inspect", true, "found work",
                            Map.of("revision", "abc123"), List.of("evidence:inspect"), CLOCK.instant());
                }),
                action("workspace.file.patch", ActionFabric.Consequence.MUTATING, request -> {
                    invoked.add("apply:" + request.inputs().get("oldText"));
                    return new ActionFabric.ActionObservation("workspace.file.patch", true, "effect applied",
                            Map.of("effect", "done"), List.of("evidence:apply"), CLOCK.instant());
                }),
                action("tool.verify", ActionFabric.Consequence.READ_ONLY, request -> {
                    invoked.add("verify:" + request.inputs().get("effect"));
                    return new ActionFabric.ActionObservation("tool.verify", true, "verified",
                            Map.of("verified", "true"), List.of("evidence:verify"), CLOCK.instant());
                })), governance.gate);
        ActionJournal journal = (a, b, c, d, e, f, cycle) -> journaled.add(cycle);
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, journal, 6, governance.gate);
        ExecutionWorkSpec work = new ExecutionWorkSpec("step-1", "inspect, mutate and verify",
                "kelvinka38/metatron-workforce", "execution.general.workspace", List.of(),
                ExecutionWorkSpec.Consequence.MUTATING, List.of("verified effect"), List.of("tool evidence"));
        GovernanceTestHarness.BoundMutation mutation = governance.bind(
                OBJECTIVE, "founder-test", WORKER, ASSIGNMENT, AUTH, "runtime-1", work);

        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(WORKER, ASSIGNMENT, AUTH,
                OBJECTIVE, work, "objective-1:step-1", mutation.context(), new CognitiveWorkerRuntime.Brain() {
                    @Override
                    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                        if (!context.memory().containsKey("revision")) {
                            return new CognitiveWorkerRuntime.Thought("tool.inspect", Map.of(), "need current revision");
                        }
                        if (!context.memory().containsKey("effect")) {
                            return new CognitiveWorkerRuntime.Thought("workspace.file.patch",
                                    Map.of("path", "README.md",
                                            "oldText", context.memory().get("revision"),
                                            "newText", "done"),
                                    "inspection identified revision to change");
                        }
                        return new CognitiveWorkerRuntime.Thought("tool.verify",
                                Map.of("effect", context.memory().get("effect")),
                                "mutation must be independently observed before completion");
                    }

                    @Override
                    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                      ActionFabric.ActionObservation observation) {
                        if (!observation.success()) return CognitiveWorkerRuntime.Reflection.failed(observation.summary());
                        if ("tool.verify".equals(observation.actionRef())
                                && "true".equals(observation.outputs().get("verified"))) {
                            return CognitiveWorkerRuntime.Reflection.complete("verified effect observed");
                        }
                        return CognitiveWorkerRuntime.Reflection.continueWith("observation changes next decision");
                    }
                });

        assertTrue(outcome.success());
        assertEquals(List.of("inspect", "apply:abc123", "verify:done"), invoked);
        assertEquals(3, outcome.cycles().size());
        assertEquals(3, journaled.size());
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, outcome.cycles().getLast().reflection().decision());
        assertTrue(outcome.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("action-fabric:action=workspace.file.patch")));
        assertTrue(outcome.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("execution-permit:")));
        assertTrue(outcome.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("cognitive-cycle:3:")));
    }

    @Test
    void actionFabricDeniesWrongWorkerWrongAuthorizationAndMutationOnReadOnlyWork() {
        ActionFabric fabric = new ActionFabric(List.of(
                action("tool.write", ActionFabric.Consequence.MUTATING,
                        request -> ActionFabric.ActionObservation.success("tool.write", "ok", Map.of(), List.of()))));

        assertThrows(SecurityException.class, () -> fabric.execute(request("tool.write", "OTHER", AUTH, true)));
        assertThrows(SecurityException.class, () -> fabric.execute(request("tool.write", WORKER, "wrong-auth", true)));
        assertThrows(SecurityException.class, () -> fabric.execute(request("tool.write", WORKER, AUTH, false)));
    }

    @Test
    void brainCannotEscapeTheGovernedActionCatalog() {
        ActionFabric fabric = new ActionFabric(List.of(
                action("tool.allowed", ActionFabric.Consequence.READ_ONLY,
                        request -> ActionFabric.ActionObservation.success("tool.allowed", "ok", Map.of(), List.of()))));
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, ActionJournal.noop(), 2);
        ExecutionWorkSpec work = new ExecutionWorkSpec("s", "read", "fixture", "c", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("done"), List.of("evidence"));

        assertThrows(SecurityException.class, () -> runtime.execute(WORKER, "assignment", AUTH, "objective", work,
                "idempotency", new CognitiveWorkerRuntime.Brain() {
                    @Override public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                        return new CognitiveWorkerRuntime.Thought("tool.forbidden", Map.of(), "attempt outside catalog");
                    }
                    @Override public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                                ActionFabric.ActionObservation observation) {
                        return CognitiveWorkerRuntime.Reflection.complete("should never execute");
                    }
                }));
    }

    private static ActionFabric.Action action(String ref, ActionFabric.Consequence consequence,
                                              java.util.function.Function<ActionFabric.ActionRequest,
                                                      ActionFabric.ActionObservation> invocation) {
        return new ActionFabric.Action() {
            @Override public String actionRef() { return ref; }
            @Override public ActionFabric.Consequence consequence() { return consequence; }
            @Override public Set<String> allowedWorkers() { return Set.of(WORKER); }
            @Override public Set<String> acceptedAuthorizations() { return Set.of(AUTH); }
            @Override public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
                return invocation.apply(request);
            }
        };
    }

    private static ActionFabric.ActionRequest request(String ref, String worker, String auth, boolean mutating) {
        return new ActionFabric.ActionRequest(ref, worker, "assignment", auth, "objective", "step",
                "idempotency", mutating, Map.of());
    }
}
