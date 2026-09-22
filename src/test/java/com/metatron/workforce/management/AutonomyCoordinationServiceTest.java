package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomyCoordinationServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void fanOutJoinAndGraphSupersessionAreDurableAndFenced() {
        Path state = temporaryDirectory.resolve("coordination.json");
        Instant t0 = Instant.parse("2026-08-31T01:00:00Z");
        AutonomyCoordinationService service = new AutonomyCoordinationService(
                new FileAutonomyCoordinationStateStore(state));
        DurableWorkGraph graph = service.ensureGraph("objective-a", List.of(
                step("a", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY),
                step("b", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY),
                step("join", List.of("a", "b"), ExecutionWorkSpec.Consequence.READ_ONLY)), t0);

        assertEquals(List.of("a", "b"), service.readyNodes("objective-a", graph.graphVersion()).stream()
                .map(node -> node.spec().stepId()).sorted().toList());
        DurableDispatch a = service.beginDispatch("objective-a", 1, "a", t0.plusSeconds(1));
        DurableDispatch b = service.beginDispatch("objective-a", 1, "b", t0.plusSeconds(1));
        service.completeDispatch(a.dispatchId(), List.of("evidence:a"), t0.plusSeconds(2));
        assertTrue(service.readyNodes("objective-a", 1).isEmpty());
        service.completeDispatch(b.dispatchId(), List.of("evidence:b"), t0.plusSeconds(3));
        assertEquals(List.of("join"), service.readyNodes("objective-a", 1).stream()
                .map(node -> node.spec().stepId()).toList());

        AutonomyCoordinationService replacement = new AutonomyCoordinationService(
                new FileAutonomyCoordinationStateStore(state));
        assertEquals(1, replacement.activeGraph("objective-a").orElseThrow().graphVersion());
        DurableWorkGraph v2 = replacement.ensureGraph("objective-a", List.of(
                step("replacement", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY)), t0.plusSeconds(4));
        assertEquals(2, v2.graphVersion());
        assertEquals(DurableWorkGraph.Status.SUPERSEDED, replacement.graphHistory("objective-a").getFirst().status());
        assertThrows(IllegalStateException.class, () -> replacement.readyNodes("objective-a", 1));
    }

    @Test
    void inboxDeduplicatesAndInterruptedEffectsRecoverFailClosed() {
        Instant t0 = Instant.parse("2026-08-31T01:00:00Z");
        AutonomyCoordinationService service = new AutonomyCoordinationService();
        assertTrue(service.acceptInbox("message-1", "effect-1", "corr", "cause", 1, "payload", t0));
        assertFalse(service.acceptInbox("message-1", "effect-1", "corr", "cause", 1, "payload", t0));
        assertFalse(service.acceptInbox("message-2", "effect-1", "corr", "cause", 1, "payload", t0));

        service.ensureGraph("objective-read", List.of(
                step("read", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY)), t0);
        service.beginDispatch("objective-read", 1, "read", t0.plusSeconds(1));
        assertTrue(service.reconcileInterrupted("objective-read", 1, t0.plusSeconds(2)).isEmpty());
        assertEquals(List.of("read"), service.readyNodes("objective-read", 1).stream()
                .map(node -> node.spec().stepId()).toList());

        service.ensureGraph("objective-write", List.of(
                step("write", List.of(), ExecutionWorkSpec.Consequence.MUTATING)), t0);
        service.beginDispatch("objective-write", 1, "write", t0.plusSeconds(1));
        assertEquals(List.of("write"), service.reconcileInterrupted("objective-write", 1, t0.plusSeconds(2)));
        assertEquals(1, service.deadLetters().size());
    }


    @Test
    void interruptedMutationUsesExplicitReconciliationResolution() {
        Instant t0 = Instant.parse("2026-09-22T00:00:00Z");
        AutonomyCoordinationService service = new AutonomyCoordinationService();

        service.ensureGraph("safe", List.of(
                step("safe-write", List.of(), ExecutionWorkSpec.Consequence.MUTATING)), t0);
        service.beginDispatch("safe", 1, "safe-write", t0.plusSeconds(1));
        assertTrue(service.reconcileInterrupted("safe", 1, java.util.Map.of(
                "safe-write", AutonomousExecutionCapability.InterruptedMutationResolution.SAFE_TO_RETRY),
                t0.plusSeconds(2)).isEmpty());
        assertEquals(List.of("safe-write"), service.readyNodes("safe", 1).stream()
                .map(node -> node.spec().stepId()).toList());

        service.ensureGraph("confirmed", List.of(
                step("confirmed-write", List.of(), ExecutionWorkSpec.Consequence.MUTATING)), t0);
        service.beginDispatch("confirmed", 1, "confirmed-write", t0.plusSeconds(1));
        assertTrue(service.reconcileInterrupted("confirmed", 1, java.util.Map.of(
                "confirmed-write", AutonomousExecutionCapability.InterruptedMutationResolution.CONFIRMED_SUCCEEDED),
                t0.plusSeconds(2)).isEmpty());
        DurableWorkGraph.Node confirmed = service.activeGraph("confirmed").orElseThrow()
                .nodes().get("confirmed-write");
        assertEquals(DurableWorkGraph.NodeStatus.SUCCEEDED, confirmed.status());
        assertTrue(confirmed.evidenceReferences().contains(
                "interrupted-mutation-reconciled:execution-attempt-succeeded"));

        service.ensureGraph("waiting", List.of(
                step("waiting-write", List.of(), ExecutionWorkSpec.Consequence.MUTATING)), t0);
        service.beginDispatch("waiting", 1, "waiting-write", t0.plusSeconds(1));
        assertTrue(service.reconcileInterrupted("waiting", 1, java.util.Map.of(
                "waiting-write", AutonomousExecutionCapability.InterruptedMutationResolution.WAIT_RETRY_LATER),
                t0.plusSeconds(2)).isEmpty());
        assertEquals(DurableWorkGraph.NodeStatus.DISPATCHED,
                service.activeGraph("waiting").orElseThrow().nodes().get("waiting-write").status());
    }


    @Test
    void replanAfterOneStepFailsPreservesOtherAlreadySucceededSteps() {
        // Production incident (2026-09-21): a 4-phase Objective (produce/prepare/verify/deliver) had
        // PRODUCE and PREPARE genuinely succeed, then VERIFY failed. The bounded autonomous replan that
        // followed built an entirely fresh graph with every node reset to PENDING -- including PRODUCE
        // and PREPARE -- forcing the Worker to redo already-completed real work from scratch on every
        // single replan, compounding cognition load instead of only re-attempting the step that actually
        // failed.
        Instant t0 = Instant.parse("2026-09-21T03:52:00Z");
        AutonomyCoordinationService service = new AutonomyCoordinationService();
        List<ExecutionWorkSpec> plan = List.of(
                step("produce", List.of(), ExecutionWorkSpec.Consequence.MUTATING),
                step("prepare", List.of("produce"), ExecutionWorkSpec.Consequence.MUTATING),
                step("verify", List.of("prepare"), ExecutionWorkSpec.Consequence.MUTATING),
                step("deliver", List.of("verify"), ExecutionWorkSpec.Consequence.MUTATING));
        DurableWorkGraph graphV1 = service.ensureGraph("objective-verify-replan", plan, t0);

        DurableDispatch produceDispatch = service.beginDispatch(
                "objective-verify-replan", graphV1.graphVersion(), "produce", t0.plusSeconds(1));
        service.completeDispatch(produceDispatch.dispatchId(), List.of("evidence:produce"), t0.plusSeconds(2));
        DurableDispatch prepareDispatch = service.beginDispatch(
                "objective-verify-replan", graphV1.graphVersion(), "prepare", t0.plusSeconds(3));
        service.completeDispatch(prepareDispatch.dispatchId(), List.of("evidence:prepare"), t0.plusSeconds(4));
        DurableDispatch verifyDispatch = service.beginDispatch(
                "objective-verify-replan", graphV1.graphVersion(), "verify", t0.plusSeconds(5));
        service.failDispatch(verifyDispatch.dispatchId(), "invalid Intelligence cognitive JSON", t0.plusSeconds(6));

        // The bounded autonomous replan re-proposes the identical plan (same 4 steps).
        DurableWorkGraph graphV2 = service.ensureGraph("objective-verify-replan", plan, t0.plusSeconds(7));

        assertEquals(2, graphV2.graphVersion());
        assertEquals(DurableWorkGraph.NodeStatus.SUCCEEDED, graphV2.nodes().get("produce").status(),
                "already-succeeded PRODUCE must survive a replan triggered by a different step's failure");
        assertEquals(List.of("evidence:produce"), graphV2.nodes().get("produce").evidenceReferences());
        assertEquals(DurableWorkGraph.NodeStatus.SUCCEEDED, graphV2.nodes().get("prepare").status(),
                "already-succeeded PREPARE must survive a replan triggered by a different step's failure");
        assertEquals(List.of("evidence:prepare"), graphV2.nodes().get("prepare").evidenceReferences());
        assertEquals(DurableWorkGraph.NodeStatus.PENDING, graphV2.nodes().get("verify").status(),
                "the step that actually failed must be re-attempted, not silently marked done");
        assertEquals(DurableWorkGraph.NodeStatus.PENDING, graphV2.nodes().get("deliver").status());

        // Only VERIFY is runnable next -- PRODUCE/PREPARE are not blindly redispatched.
        assertEquals(List.of("verify"), service.readyNodes("objective-verify-replan", graphV2.graphVersion())
                .stream().map(node -> node.spec().stepId()).toList());
    }

    @Test
    void replanNeverCarriesForwardASucceededStepWhoseSpecActuallyChanged() {
        // Carry-forward must key on more than stepId: if the replanned step with the same id is not
        // byte-for-byte the same work (e.g. the planner also revised its objective text), it must be
        // re-attempted, never silently treated as already-done merely because an earlier attempt of a
        // same-named step once succeeded.
        Instant t0 = Instant.parse("2026-09-21T03:52:00Z");
        AutonomyCoordinationService service = new AutonomyCoordinationService();
        List<ExecutionWorkSpec> plan = List.of(
                step("produce", List.of(), ExecutionWorkSpec.Consequence.MUTATING),
                step("verify", List.of("produce"), ExecutionWorkSpec.Consequence.MUTATING));
        DurableWorkGraph graphV1 = service.ensureGraph("objective-genuine-replan", plan, t0);
        DurableDispatch produceDispatch = service.beginDispatch(
                "objective-genuine-replan", graphV1.graphVersion(), "produce", t0.plusSeconds(1));
        service.completeDispatch(produceDispatch.dispatchId(), List.of("evidence:produce"), t0.plusSeconds(2));
        DurableDispatch verifyDispatch = service.beginDispatch(
                "objective-genuine-replan", graphV1.graphVersion(), "verify", t0.plusSeconds(3));
        service.failDispatch(verifyDispatch.dispatchId(), "boom", t0.plusSeconds(4));

        ExecutionWorkSpec changedProduce = new ExecutionWorkSpec(
                "produce", "a materially different objective text this time", "target", "test.capability",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        DurableWorkGraph graphV2 = service.ensureGraph("objective-genuine-replan",
                List.of(changedProduce, plan.get(1)), t0.plusSeconds(5));

        assertEquals(DurableWorkGraph.NodeStatus.PENDING, graphV2.nodes().get("produce").status(),
                "a step whose spec actually changed must never be carried forward as already-succeeded");
    }

    private static ExecutionWorkSpec step(String id, List<String> dependencies,
                                           ExecutionWorkSpec.Consequence consequence) {
        return new ExecutionWorkSpec(id, id, "target", "test.capability", dependencies, consequence);
    }
}
