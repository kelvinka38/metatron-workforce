package com.metatron.workforce.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ManagementAutonomyServiceTest {

    @Test
    void directorOwnsObjectiveDetectsStaffingGapRecoversAndDeliversWithoutHumanOrchestration() {
        ManagementAutonomyService service = new ManagementAutonomyService();
        Instant t0 = Instant.parse("2026-08-28T00:00:00Z");

        ManagementObjective objective = service.acceptObjective(
                "obj-gateway-v2",
                "worker-gateway-director",
                "org-metatron-gateway",
                "Deliver Gateway V2 according to approved specification",
                t0);

        assertEquals(ManagementObjective.Status.ACTIVE, objective.status());
        assertEquals("worker-gateway-director", objective.ownerWorkerId());

        StaffingNeed staffingNeed = service.assessCapacity(
                        objective.objectiveId(),
                        objective.ownerWorkerId(),
                        "gateway.security.review",
                        3.0,
                        1.0,
                        t0.plusSeconds(10))
                .orElseThrow();

        assertEquals(2.0, staffingNeed.capacityGap());
        assertEquals("worker-gateway-director", staffingNeed.managerWorkerId());

        service.addAssignmentReference(
                objective.objectiveId(),
                objective.ownerWorkerId(),
                "assignment-gateway-engineering",
                t0.plusSeconds(20));
        service.addAssignmentReference(
                objective.objectiveId(),
                objective.ownerWorkerId(),
                "assignment-gateway-security",
                t0.plusSeconds(30));

        ManagementObjective blocked = service.markBlocked(
                objective.objectiveId(),
                objective.ownerWorkerId(),
                "security worker unavailable",
                t0.plusSeconds(40));
        assertEquals(ManagementObjective.Status.BLOCKED, blocked.status());

        ManagementObjective recovered = service.recoverLocally(
                objective.objectiveId(),
                objective.ownerWorkerId(),
                "reassign security review to qualified available worker and reschedule dependent work",
                t0.plusSeconds(50));
        assertEquals(ManagementObjective.Status.ACTIVE, recovered.status());
        assertEquals("worker-gateway-director", recovered.ownerWorkerId());
        assertEquals(2, recovered.assignmentRefs().size());

        ManagementObjective delivered = service.deliver(
                objective.objectiveId(),
                objective.ownerWorkerId(),
                List.of("evidence:g12", "evidence:gateway-v2-acceptance"),
                t0.plusSeconds(60));

        assertEquals(ManagementObjective.Status.DELIVERED, delivered.status());
        assertTrue(delivered.terminal());
        assertEquals(2, delivered.evidenceRefs().size());

        List<ManagementAutonomyService.ManagementEvent> history = service.history(objective.objectiveId());
        assertTrue(history.stream().anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.STAFFING_NEED_DETECTED));
        assertTrue(history.stream().anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY));
        assertFalse(history.stream().anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.ESCALATED));
    }

    @Test
    void deliveryFailsClosedWithoutEvidenceAndNonOwnerCannotOperateObjective() {
        ManagementAutonomyService service = new ManagementAutonomyService();
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        service.acceptObjective("obj-1", "worker-director", "org-1", "Deliver bounded objective", now);

        assertThrows(SecurityException.class, () -> service.addAssignmentReference(
                "obj-1", "worker-other", "assignment-1", now.plusSeconds(1)));

        assertThrows(IllegalArgumentException.class, () -> service.deliver(
                "obj-1", "worker-director", List.of(), now.plusSeconds(2)));
    }

    @Test
    void sufficientCapacityDoesNotManufactureStaffingNeed() {
        ManagementAutonomyService service = new ManagementAutonomyService();
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        service.acceptObjective("obj-2", "worker-director", "org-1", "Operate service", now);

        assertTrue(service.assessCapacity(
                "obj-2", "worker-director", "gateway.operations", 2.0, 3.0, now.plusSeconds(1)).isEmpty());
    }
}
