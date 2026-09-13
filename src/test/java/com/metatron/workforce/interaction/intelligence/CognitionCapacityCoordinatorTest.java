package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitionCapacityCoordinatorTest {
    @TempDir Path tempDir;

    @Test
    void saturationQueuesWithinMetatronOwnedLaneAndRecoversWithoutOverlap() throws Exception {
        PersistentCognitionCapacityEventStore store = store("queue-events.jsonl");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        MetatronCognitionClient delegate = request -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                if (request.requestId().equals("REQ-1")) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test latch timeout");
                    }
                }
                return response(request.requestId());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                active.decrementAndGet();
            }
        };

        CognitionCapacityCoordinator coordinator = new CognitionCapacityCoordinator(
                delegate, store, 1, 1, Duration.ofSeconds(2));

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> coordinator.reason(request("REQ-1")));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            var second = pool.submit(() -> coordinator.reason(request("REQ-2")));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (store.events().stream().noneMatch(event -> event.requestId().equals("REQ-2")
                    && event.eventType().equals("CognitionRequestQueued"))
                    && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(store.events().stream().anyMatch(event -> event.requestId().equals("REQ-2")
                    && event.eventType().equals("CognitionRequestQueued")));
            assertEquals(1, coordinator.queuedCount());

            releaseFirst.countDown();
            assertEquals("REQ-1", first.get(2, TimeUnit.SECONDS).requestReference());
            assertEquals("REQ-2", second.get(2, TimeUnit.SECONDS).requestReference());
        }

        assertEquals(1, maxActive.get());
        assertTrue(store.events().stream().anyMatch(event -> event.requestId().equals("REQ-2")
                && event.eventType().equals("CognitionCapacityRecovered")));
        assertTrue(store.events().stream().anyMatch(event -> event.requestId().equals("REQ-2")
                && event.state() == CognitionRequestState.SUCCEEDED));
    }

    @Test
    void fullQueueFailsTruthfullyAndNeverInvokesSecondInference() throws Exception {
        PersistentCognitionCapacityEventStore store = store("full-events.jsonl");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        MetatronCognitionClient delegate = request -> {
            calls.incrementAndGet();
            if (request.requestId().equals("REQ-A")) {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return response(request.requestId());
        };
        CognitionCapacityCoordinator coordinator = new CognitionCapacityCoordinator(
                delegate, store, 1, 0, Duration.ofMillis(50));

        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> coordinator.reason(request("REQ-A")));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> coordinator.reason(request("REQ-B")));
            assertTrue(failure.getMessage().contains("capacity_queue_full"));
            assertEquals(1, calls.get());
            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
        }

        assertTrue(store.events().stream().anyMatch(event -> event.requestId().equals("REQ-B")
                && event.state() == CognitionRequestState.FAILED_RETRYABLE));
    }

    @Test
    void restartMarksIncompleteRequestForReconciliationAndCompletedRequestIsNotDuplicated() {
        Path path = tempDir.resolve("restart-events.jsonl");
        PersistentCognitionCapacityEventStore before = new PersistentCognitionCapacityEventStore(path, new ObjectMapper());
        before.append(CognitionCapacityEvent.transition(request("REQ-RUNNING"), CognitionRequestState.RUNNING, "running"));
        before.append(CognitionCapacityEvent.transition(request("REQ-DONE"), CognitionRequestState.SUCCEEDED, "done"));

        PersistentCognitionCapacityEventStore after = new PersistentCognitionCapacityEventStore(path, new ObjectMapper());
        assertEquals(1, after.reconcileIncomplete());
        assertEquals(CognitionRequestState.RECONCILIATION_REQUIRED,
                after.latest("REQ-RUNNING").orElseThrow().state());
        assertEquals(CognitionRequestState.SUCCEEDED, after.latest("REQ-DONE").orElseThrow().state());

        AtomicInteger calls = new AtomicInteger();
        CognitionCapacityCoordinator coordinator = new CognitionCapacityCoordinator(
                request -> {
                    calls.incrementAndGet();
                    return response(request.requestId());
                }, after, 1, 1, Duration.ofMillis(50));

        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> coordinator.reason(request("REQ-DONE")));
        assertTrue(duplicate.getMessage().contains("already_succeeded"));
        assertEquals(0, calls.get());
    }

    private PersistentCognitionCapacityEventStore store(String name) {
        return new PersistentCognitionCapacityEventStore(tempDir.resolve(name), new ObjectMapper());
    }

    private static MetatronCognitionClient.Request request(String requestId) {
        return new MetatronCognitionClient.Request(
                requestId,
                "worker.cognition",
                "perform useful institutional work",
                "context",
                List.of("worker-cognition-input:" + requestId),
                IntelligenceOriginContext.worker(
                        "WORKER-TEST", "OBJ-TEST", "ASG-TEST", "STEP-TEST", "ATT-TEST",
                        "worker.cognition", requestId),
                "strict structured cognitive result");
    }

    private static MetatronCognitionClient.Response response(String requestId) {
        return new MetatronCognitionClient.Response(
                "{\"decision\":\"COMPLETE\"}", "metatron-node-test", "open-weight-test", 10, 5, requestId);
    }
}
