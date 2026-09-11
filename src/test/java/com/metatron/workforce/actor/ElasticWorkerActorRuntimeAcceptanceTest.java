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
}
