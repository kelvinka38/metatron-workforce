package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedAutonomousExecutionCapabilityTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void allocationAssignmentAndAdmissionExistBeforeEffectAndCapacityReleasesAfterSuccess() {
        WorkforceCoreService core = seededCore("worker-a", 1.0);
        AtomicInteger effects = new AtomicInteger();

        AutonomousExecutionCapability delegate = new TestCapability("worker-a") {
            @Override public CapabilityResult execute(CapabilityRequest request) {
                assertTrue(request.allocated());
                assertEquals(0.0, core.remainingCapacity("worker-a"), 0.000001);
                var assignment = core.allAssignments().stream()
                        .filter(a -> a.assignmentId().equals(request.assignmentReference()))
                        .findFirst().orElseThrow();
                assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE, assignment.status());
                effects.incrementAndGet();
                return success(request, "work-a");
            }
        };

        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(1));
        var result = governed.execute(request("objective-a", "step-a"));

        assertEquals(1, effects.get());
        assertEquals("worker-a", result.workerId());
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.startsWith("allocation:worker=worker-a")));
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.contains("state=ADMITTED")));
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, core.allAssignments().getFirst().status());
        assertEquals(WorkforceCoreService.ReservationStatus.RELEASED,
                core.allCapacityReservations().getFirst().status());
        assertEquals(1.0, core.remainingCapacity("worker-a"), 0.000001);
    }

    @Test
    void successfulEffectWithoutEvidenceBlocksAssignmentCompletion() {
        WorkforceCoreService core = seededCore("worker-a", 1.0);
        AutonomousExecutionCapability delegate = new TestCapability("worker-a") {
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work-without-evidence", List.of(), "PASS");
            }
        };
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO);

        var result = governed.execute(request("objective-no-evidence", "step-no-evidence"));

        assertFalse(result.success());
        assertEquals("reason=evidence_missing", result.summary());
        assertEquals(List.of("reason=evidence_missing"), result.evidenceReferences());
        assertEquals(WorkforceCoreService.AssignmentStatus.BLOCKED,
                core.allAssignments().getFirst().status());
    }

    @Test
    void missingAuthorizationFailsClosedBeforeReservationOrEffect() {
        WorkforceCoreService core = seededCore("worker-a", 1.0);
        AtomicInteger effects = new AtomicInteger();
        AutonomousExecutionCapability delegate = new TestCapability("worker-a") {
            @Override public String authorizationReference() { return ""; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                return success(request, "forbidden");
            }
        };
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO);

        assertThrows(SecurityException.class, () -> governed.execute(request("objective-a", "step-a")));
        assertEquals(0, effects.get());
        assertTrue(core.allAssignments().isEmpty());
        assertTrue(core.allCapacityReservations().isEmpty());
        assertEquals(1.0, core.remainingCapacity("worker-a"), 0.000001);
    }

    @Test
    void competingDispatchesCannotOverlapBeyondFiniteWorkerCapacity() throws Exception {
        WorkforceCoreService core = seededCore("worker-a", 1.0);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        AutonomousExecutionCapability delegate = new TestCapability("worker-a") {
            @Override public CapabilityResult execute(CapabilityRequest request) {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    if (request.workSpec().stepId().equals("step-a")) {
                        firstEntered.countDown();
                        if (!releaseFirst.await(1, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timeout");
                        }
                    }
                    return success(request, "work-" + request.workSpec().stepId());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    active.decrementAndGet();
                }
            }
        };

        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, Duration.ofSeconds(2));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AutonomousExecutionCapability.CapabilityResult> first =
                    pool.submit(() -> governed.execute(request("objective-a", "step-a")));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<AutonomousExecutionCapability.CapabilityResult> second =
                    pool.submit(() -> governed.execute(request("objective-a", "step-b")));

            Thread.sleep(100);
            assertEquals(1, active.get(), "second effect must wait for finite capacity");
            assertEquals(0.0, core.remainingCapacity("worker-a"), 0.000001);
            releaseFirst.countDown();

            assertTrue(first.get(2, TimeUnit.SECONDS).success());
            assertTrue(second.get(2, TimeUnit.SECONDS).success());
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertEquals(1, maxActive.get(), "capacity=1 must serialize effects on the Worker");
        assertEquals(2, core.allAssignments().size());
        assertTrue(core.allAssignments().stream().allMatch(a -> a.status() == WorkforceCoreService.AssignmentStatus.COMPLETED));
        assertEquals(1.0, core.remainingCapacity("worker-a"), 0.000001);
    }

    private static WorkforceCoreService seededCore(String workerId, double capacity) {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant-" + workerId, WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker(workerId, "participant-" + workerId);
        core.participate("participation-" + workerId, workerId, "org-metatron", "position", "role");
        core.attestCapability(workerId, "test.capability", 1.0, "evidence:capability");
        core.setAvailability(workerId, true, capacity);
        return core;
    }

    private static AutonomousExecutionCapability.CapabilityRequest request(String objectiveId, String stepId) {
        return new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", objectiveId,
                new ExecutionWorkSpec(stepId, "Execute " + stepId, "target", "test.capability", List.of(),
                        ExecutionWorkSpec.Consequence.READ_ONLY));
    }

    private abstract static class TestCapability implements AutonomousExecutionCapability {
        private final String workerId;
        TestCapability(String workerId) { this.workerId = workerId; }
        @Override public String capabilityRef() { return "test.capability"; }
        @Override public String authorityReference() { return "authority:test"; }
        @Override public String authorizationReference() { return "authorization:test"; }
        @Override public boolean supportsWorker(String candidate) { return workerId.equals(candidate); }

        CapabilityResult success(CapabilityRequest request, String workRef) {
            return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                    workRef, List.of("evidence:" + workRef), "PASS");
        }
    }
}
