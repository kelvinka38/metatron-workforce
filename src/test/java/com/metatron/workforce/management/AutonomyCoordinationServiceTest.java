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

    private static ExecutionWorkSpec step(String id, List<String> dependencies,
                                           ExecutionWorkSpec.Consequence consequence) {
        return new ExecutionWorkSpec(id, id, "target", "test.capability", dependencies, consequence);
    }
}
