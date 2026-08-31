package com.metatron.workforce.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkforceCapacityReservationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void finiteCapacityIsReservedPersistedAndReleasedByTerminalAssignment() {
        Path state = temporaryDirectory.resolve("core.json");
        WorkforceCoreService core = new WorkforceCoreService(new FileWorkforceCoreStateStore(state));
        seed(core, "worker-a", 1.0);

        core.reserveCapacity("reservation-a", "assignment-a", "objective-a", "worker-a", 0.75);
        assertEquals(0.25, core.remainingCapacity("worker-a"), 0.000001);
        assertThrows(IllegalStateException.class, () ->
                core.reserveCapacity("reservation-b", "assignment-b", "objective-b", "worker-a", 0.50));

        WorkforceCoreService replacement = new WorkforceCoreService(new FileWorkforceCoreStateStore(state));
        assertEquals(0.25, replacement.remainingCapacity("worker-a"), 0.000001);
        assertTrue(replacement.eligibleWorkers("repo.audit", 1.0, 0.30, Instant.now()).isEmpty());

        replacement.assignReserved("reservation-a", "participation-worker-a",
                "authority:a", "authorization:a", "audit");
        replacement.transitionAssignment("assignment-a", WorkforceCoreService.AssignmentStatus.COMPLETED);
        assertEquals(1.0, replacement.remainingCapacity("worker-a"), 0.000001);
        assertEquals(WorkforceCoreService.ReservationStatus.RELEASED,
                replacement.allCapacityReservations().getFirst().status());
    }

    @Test
    void reservationReplayIsIdempotentButConflictingReplayFailsClosed() {
        WorkforceCoreService core = new WorkforceCoreService();
        seed(core, "worker-a", 2.0);
        var first = core.reserveCapacity("reservation-a", "assignment-a", "objective-a", "worker-a", 1.0);
        var replay = core.reserveCapacity("reservation-a", "assignment-a", "objective-a", "worker-a", 1.0);
        assertEquals(first, replay);
        assertThrows(IllegalStateException.class, () ->
                core.reserveCapacity("reservation-a", "assignment-other", "objective-a", "worker-a", 1.0));
    }

    private static void seed(WorkforceCoreService core, String workerId, double capacity) {
        String participantId = "participant-" + workerId;
        core.recognizeParticipant(participantId, WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker(workerId, participantId);
        core.participate("participation-" + workerId, workerId, "org-metatron", "position", "role");
        core.attestCapability(workerId, "repo.audit", 1.0, "evidence:capability");
        core.setAvailability(workerId, true, capacity);
    }
}
