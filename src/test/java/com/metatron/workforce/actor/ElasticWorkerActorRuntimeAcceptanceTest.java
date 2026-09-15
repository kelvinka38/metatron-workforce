package com.metatron.workforce.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ElasticWorkerActorRuntimeAcceptanceTest {

    @Test
    void workerCountIsIndependentFromComputeConcurrency() {
        try (WorkerActorRuntime runtime = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 3)) {
            int n = 257; // test parameter only; deliberately not a round architectural ceiling.
            for (int i = 0; i < n; i++) runtime.ensureActor("WORKER-SCALE-" + i);

            assertEquals(n, runtime.allActors().size());
            assertEquals(n, runtime.stats().registeredActors());
            assertEquals(3, runtime.stats().availableComputePermits());
            assertTrue(runtime.allActors().stream().allMatch(actor -> actor.actorId().equals("actor:" + actor.workerId())));
        }
    }

    @Test
    void differentWorkersCanWorkConcurrentlyButSameWorkerIsSerialized() throws Exception {
        try (WorkerActorRuntime runtime = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 4)) {
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();
            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            var callers = Executors.newFixedThreadPool(2);
            try {
                var a = callers.submit(() -> runtime.runTurn(
                        "WORKER-A", WorkerActorMessage.Type.SYSTEM, "test", "OBJ-A", "ASG-A", "S1", "worker.cognition", "A",
                        () -> {
                            int now = concurrent.incrementAndGet();
                            maxConcurrent.accumulateAndGet(now, Math::max);
                            bothStarted.countDown();
                            assertTrue(release.await(2, TimeUnit.SECONDS));
                            concurrent.decrementAndGet();
                            return "A";
                        }));
                var b = callers.submit(() -> runtime.runTurn(
                        "WORKER-B", WorkerActorMessage.Type.SYSTEM, "test", "OBJ-B", "ASG-B", "S1", "worker.cognition", "B",
                        () -> {
                            int now = concurrent.incrementAndGet();
                            maxConcurrent.accumulateAndGet(now, Math::max);
                            bothStarted.countDown();
                            assertTrue(release.await(2, TimeUnit.SECONDS));
                            concurrent.decrementAndGet();
                            return "B";
                        }));
                assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
                release.countDown();
                assertEquals("A", a.get(2, TimeUnit.SECONDS));
                assertEquals("B", b.get(2, TimeUnit.SECONDS));
                assertTrue(maxConcurrent.get() >= 2, "different Worker actors should overlap when compute capacity permits");
            } finally {
                callers.shutdownNow();
            }

            AtomicInteger sameConcurrent = new AtomicInteger();
            AtomicInteger sameMax = new AtomicInteger();
            var sameCallers = Executors.newFixedThreadPool(2);
            try {
                var first = sameCallers.submit(() -> runtime.runTurn(
                        "WORKER-SERIAL", WorkerActorMessage.Type.SYSTEM, "test", "O1", "A1", "S1", "worker.cognition", "one",
                        () -> {
                            int now = sameConcurrent.incrementAndGet();
                            sameMax.accumulateAndGet(now, Math::max);
                            Thread.sleep(120);
                            sameConcurrent.decrementAndGet();
                            return 1;
                        }));
                var second = sameCallers.submit(() -> runtime.runTurn(
                        "WORKER-SERIAL", WorkerActorMessage.Type.SYSTEM, "test", "O2", "A2", "S2", "worker.cognition", "two",
                        () -> {
                            int now = sameConcurrent.incrementAndGet();
                            sameMax.accumulateAndGet(now, Math::max);
                            Thread.sleep(40);
                            sameConcurrent.decrementAndGet();
                            return 2;
                        }));
                assertEquals(1, first.get(2, TimeUnit.SECONDS));
                assertEquals(2, second.get(2, TimeUnit.SECONDS));
                assertEquals(1, sameMax.get(), "one Worker actor must own one serial work lane");
            } finally {
                sameCallers.shutdownNow();
            }
        }
    }

    @Test
    void oneWorkerCanBePausedWhileAnotherKeepsWorking() {
        try (WorkerActorRuntime runtime = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 2)) {
            runtime.ensureActor("WORKER-PAUSED");
            runtime.ensureActor("WORKER-ACTIVE");
            runtime.pause("WORKER-PAUSED");

            assertThrows(IllegalStateException.class, () -> runtime.runTurn(
                    "WORKER-PAUSED", WorkerActorMessage.Type.HUMAN_MESSAGE, "human-primary", "", "", "", "worker.live.conversation", "hello",
                    () -> "should not run"));

            String value = runtime.runTurn(
                    "WORKER-ACTIVE", WorkerActorMessage.Type.HUMAN_MESSAGE, "human-primary", "", "", "", "worker.live.conversation", "hello",
                    () -> "working");
            assertEquals("working", value);
            assertEquals(WorkerActorState.PAUSED, runtime.requireActor("WORKER-PAUSED").state());
            assertEquals(WorkerActorState.IDLE, runtime.requireActor("WORKER-ACTIVE").state());
        }
    }

    @Test
    void actorIdentityAndMailboxSurviveRuntimeReplacement(@TempDir Path temp) {
        Path state = temp.resolve("worker-actors");
        String actorId;
        try (WorkerActorRuntime first = new WorkerActorRuntime(
                new FileWorkerActorStateStore(state, new ObjectMapper()), 2)) {
            actorId = first.ensureActor("WORKER-DURABLE").actorId();
            first.delegate("WORKER-DURABLE", "WORKER-TARGET", "OBJ-1", "Please review this work");
            assertEquals(1, first.mailboxDepth("WORKER-TARGET"));
        }

        try (WorkerActorRuntime restarted = new WorkerActorRuntime(
                new FileWorkerActorStateStore(state, new ObjectMapper()), 2)) {
            assertEquals(actorId, restarted.requireActor("WORKER-DURABLE").actorId());
            assertEquals("actor:WORKER-TARGET", restarted.requireActor("WORKER-TARGET").actorId());
            assertEquals(1, restarted.mailboxDepth("WORKER-TARGET"));
            WorkerActorMessage delegation = restarted.mailbox("WORKER-TARGET").getFirst();
            assertEquals(WorkerActorMessage.Type.DELEGATION, delegation.type());
            assertEquals("worker:WORKER-DURABLE", delegation.senderRef());
        }
    }

    @Test
    void interruptedClaimedTurnBecomesReconciliationRequiredOnRestart() {
        InMemoryWorkerActorStateStore store = new InMemoryWorkerActorStateStore();
        Instant now = Instant.parse("2026-09-11T10:00:00Z");
        WorkerActorSnapshot working = WorkerActorSnapshot.create("WORKER-RECOVER", now)
                .withState(WorkerActorState.WORKING, "OBJ", "ASG", "STEP", "working", "finish", 1, now);
        WorkerActorMessage claimed = new WorkerActorMessage(
                "actor-message:claimed", "WORKER-RECOVER", WorkerActorMessage.Type.ASSIGNMENT,
                WorkerActorMessage.Status.CLAIMED, "workforce:assignment", "OBJ", "ASG", "STEP", "cap", "work",
                now, now, null, "");
        store.seed(new WorkerActorStateStore.Snapshot(
                Map.of("WORKER-RECOVER", working), Map.of("WORKER-RECOVER", List.of(claimed))));

        try (WorkerActorRuntime restarted = new WorkerActorRuntime(store, 1)) {
            assertEquals(WorkerActorState.RECOVERING, restarted.requireActor("WORKER-RECOVER").state());
            assertEquals(WorkerActorMessage.Status.RECONCILIATION_REQUIRED,
                    restarted.mailbox("WORKER-RECOVER").getFirst().status());
        }
    }

    @Test
    void acceptsNewWorkReflectsActualActorReadiness() throws Exception {
        // Root-cause fix (WORKFORCE RUNTIME RE-FOUNDATION, part 2 of 2): worker selection previously had
        // zero awareness of actor state and could hand new work to a PAUSED or already-WORKING actor.
        try (WorkerActorRuntime runtime = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 2)) {
            assertTrue(runtime.acceptsNewWork("WORKER-UNKNOWN"),
                    "an unseen worker will be lazily created IDLE, so it currently accepts work");

            runtime.ensureActor("WORKER-IDLE");
            assertTrue(runtime.acceptsNewWork("WORKER-IDLE"));

            runtime.ensureActor("WORKER-PAUSED");
            runtime.pause("WORKER-PAUSED");
            assertFalse(runtime.acceptsNewWork("WORKER-PAUSED"));

            runtime.ensureActor("WORKER-OFFLINE");
            runtime.offline("WORKER-OFFLINE");
            assertFalse(runtime.acceptsNewWork("WORKER-OFFLINE"));

            // BLOCKED must remain eligible: it is the state a Worker ends up in after a failed turn,
            // and bounded recovery re-dispatches to the SAME Worker, not a different one.
            assertTrue(runtime.acceptsNewWork("WORKER-UNKNOWN-2"));
            runtime.ensureActor("WORKER-BLOCKED");
            try {
                runtime.runTurn("WORKER-BLOCKED", WorkerActorMessage.Type.SYSTEM, "test", "OBJ", "ASG", "S1",
                        "worker.cognition", "work", () -> { throw new IllegalStateException("simulated turn failure"); });
            } catch (IllegalStateException expected) { /* actor is now BLOCKED */ }
            assertEquals(WorkerActorState.BLOCKED, runtime.requireActor("WORKER-BLOCKED").state());
            assertTrue(runtime.acceptsNewWork("WORKER-BLOCKED"),
                    "a BLOCKED actor must remain eligible so recovery can retry the same Worker");

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var callers = Executors.newSingleThreadExecutor();
            try {
                runtime.ensureActor("WORKER-BUSY");
                var future = callers.submit(() -> runtime.runTurn(
                        "WORKER-BUSY", WorkerActorMessage.Type.SYSTEM, "test", "OBJ", "ASG", "S1",
                        "worker.cognition", "work", () -> {
                            started.countDown();
                            assertTrue(release.await(2, TimeUnit.SECONDS));
                            return "done";
                        }));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertFalse(runtime.acceptsNewWork("WORKER-BUSY"), "a WORKING actor must not accept a second turn");
                release.countDown();
                assertEquals("done", future.get(2, TimeUnit.SECONDS));
                assertTrue(runtime.acceptsNewWork("WORKER-BUSY"), "actor is free again once its turn completes");
            } finally {
                callers.shutdownNow();
            }
        }
    }

    @Test
    void concurrentConsumeAssignmentCallsExecuteTheRealEffectExactlyOnce() throws Exception {
        // CAS fix (2026-09-15): two callers racing WorkerActorRuntime.consumeAssignment() for the SAME
        // still-PENDING Assignment (e.g. AutonomousManagementRunner's own dispatch and
        // WorkerActorAssignmentSupervisor's restart-reconciliation scan hitting the same Assignment in
        // the same window) previously could both pass the pre-submit not-terminal/not-claimed check and
        // both get an executeMessage task queued. The second task's snapshot of the message, captured
        // before it reached the front of the executor queue, still read PENDING even after the first had
        // already completed it -- so it re-claimed and ran the real effect a second time. This proves
        // exactly that race, at the actual public API surface real callers use, and asserts the effect
        // ran once.
        try (WorkerActorRuntime runtime = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 4)) {
            runtime.ensureActor("WORKER-CAS");
            com.metatron.workforce.core.WorkforceCoreService.Assignment assignment =
                    new com.metatron.workforce.core.WorkforceCoreService.Assignment(
                            "assignment:cas-race", "OBJ-CAS", "WORKER-CAS", "participation:cas",
                            "authority:test", "authorization:test", "do the real effect",
                            com.metatron.workforce.core.WorkforceCoreService.AssignmentStatus.ACTIVE,
                            Instant.parse("2026-09-15T00:00:00Z"));

            AtomicInteger effects = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            java.util.concurrent.Callable<String> work = () -> {
                assertTrue(release.await(2, TimeUnit.SECONDS));
                effects.incrementAndGet();
                return "real-effect";
            };

            var callers = Executors.newFixedThreadPool(2);
            try {
                // Both callers race consumeAssignment() for the identical Assignment before either
                // task has had a chance to run, matching the actual race window in production between
                // two independent dispatch paths hitting the same still-PENDING Assignment.
                var first = callers.submit(() -> runtime.consumeAssignment(assignment, work));
                var second = callers.submit(() -> runtime.consumeAssignment(assignment, work));
                var firstFuture = first.get(2, TimeUnit.SECONDS);
                var secondFuture = second.get(2, TimeUnit.SECONDS);

                Thread.sleep(150);
                release.countDown();

                if (firstFuture != null) firstFuture.get(2, TimeUnit.SECONDS);
                if (secondFuture != null) secondFuture.get(2, TimeUnit.SECONDS);
                assertEquals(1, effects.get(), "the real effect must run exactly once despite the race");
            } finally {
                callers.shutdownNow();
            }
        }
    }
}


