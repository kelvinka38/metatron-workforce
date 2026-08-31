package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedCapacityReconciliationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void terminalExecutionAttemptReleasesOrphanedCoreCapacityBeforeNextAdmission() {
        WorkforceCoreService core = seededCore();
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(new RuntimeRegistry());
        createPriorAssignment(core, "stale-assignment", "stale-reservation", "objective-stale");
        ExecutionAttempt stale = attempts.begin(
                "dispatch-stale", "objective-stale", "step-stale", "worker-a",
                "stale-assignment", "authorization:test", "runtime-stale", 1,
                Duration.ofSeconds(30), CLOCK.instant());
        attempts.fail(stale.attemptId(), stale.fencingToken(), "process-replaced", CLOCK.instant());

        AtomicInteger effects = new AtomicInteger();
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                capability(effects), core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO, null, attempts, runtimes);

        var result = governed.execute(request("objective-next", "step-next"));

        assertTrue(result.success());
        assertEquals(1, effects.get());
        assertEquals(WorkforceCoreService.AssignmentStatus.CANCELLED,
                core.allAssignments().stream().filter(a -> a.assignmentId().equals("stale-assignment")).findFirst().orElseThrow().status());
        assertEquals(WorkforceCoreService.ReservationStatus.RELEASED,
                core.allCapacityReservations().stream().filter(r -> r.reservationId().equals("stale-reservation")).findFirst().orElseThrow().status());
        assertEquals(1.0, core.remainingCapacity("worker-a"), 0.000001);
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.startsWith("capacity-reconciled:assignment=stale-assignment")));
    }

    @Test
    void liveExecutionLeaseIsNeverReclaimedForCompetingAdmission() {
        WorkforceCoreService core = seededCore();
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(new RuntimeRegistry());
        createPriorAssignment(core, "live-assignment", "live-reservation", "objective-live");
        attempts.begin(
                "dispatch-live", "objective-live", "step-live", "worker-a",
                "live-assignment", "authorization:test", "runtime-live", 1,
                Duration.ofSeconds(30), CLOCK.instant());

        AtomicInteger effects = new AtomicInteger();
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                capability(effects), core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO, null, attempts, runtimes);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> governed.execute(request("objective-competing", "step-competing")));

        assertEquals("capacity-unavailable:test.read", failure.getMessage());
        assertEquals(0, effects.get());
        assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE,
                core.allAssignments().stream().filter(a -> a.assignmentId().equals("live-assignment")).findFirst().orElseThrow().status());
        assertEquals(WorkforceCoreService.ReservationStatus.ACTIVE,
                core.allCapacityReservations().stream().filter(r -> r.reservationId().equals("live-reservation")).findFirst().orElseThrow().status());
        assertEquals(0.0, core.remainingCapacity("worker-a"), 0.000001);
    }

    private static WorkforceCoreService seededCore() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant-a", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker-a", "participant-a");
        core.participate("participation-a", "worker-a", "org-metatron", "position", "role");
        core.attestCapability("worker-a", "test.read", 1.0, "evidence:capability");
        core.setAvailability("worker-a", true, 1.0);
        return core;
    }

    private static void createPriorAssignment(WorkforceCoreService core, String assignmentId,
                                              String reservationId, String objectiveId) {
        core.reserveCapacity(reservationId, assignmentId, objectiveId, "worker-a", 1.0);
        core.assignReserved(reservationId, "participation-a", "authority:test", "authorization:test", "prior work");
    }

    private static AutonomousExecutionCapability capability(AtomicInteger effects) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.read"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public boolean supportsWorker(String workerId) { return workerId.equals("worker-a"); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work:" + request.workSpec().stepId(), List.of("evidence:effect"), "PASS");
            }
        };
    }

    private static AutonomousExecutionCapability.CapabilityRequest request(String objectiveId, String stepId) {
        return new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", objectiveId,
                new ExecutionWorkSpec(stepId, "Read repository", "repo", "test.read", List.of(),
                        ExecutionWorkSpec.Consequence.READ_ONLY))
                .withDispatch(objectiveId + ":graph:1:step:" + stepId + ":attempt:1", 1);
    }
}
