package com.metatron.workforce.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LiveManagementControllerAcceptanceTest {
    @TempDir Path temp;

    @Test
    void founderCanDelegateObjectiveToDirectorAndDirectorStateSurvivesServiceReplacement() {
        Path state = temp.resolve("management.json");
        ManagementAutonomyService first = new ManagementAutonomyService(new FileManagementStateStore(state));
        LiveManagementController controller = new LiveManagementController(first);

        ManagementObjective objective = controller.appointObjective("HUMAN-FOUNDER-001",
                new LiveManagementController.ObjectiveCommand("OBJ-GATEWAY-V2-001",
                        "WORKER-GATEWAY-DIRECTOR-001", "ORG-GATEWAY",
                        "Deliver Gateway V2 according to approved specification",
                        "HUMAN-FOUNDER-001", "AUTHORITY-FOUNDER-001", "AUTHORIZATION-GATEWAY-V2-001"));
        assertEquals(ManagementObjective.Status.ACTIVE, objective.status());
        assertEquals("WORKER-GATEWAY-DIRECTOR-001", objective.ownerWorkerId());

        LiveManagementController.StaffingAssessment staffing = controller.assessCapacity(objective.objectiveId(),
                objective.ownerWorkerId(), new LiveManagementController.CapacityCommand("gateway-engineering", 2, 0));
        assertNotNull(staffing.staffingNeed());
        assertEquals(2.0, staffing.staffingNeed().capacityGap());

        controller.referenceAssignment(objective.objectiveId(), objective.ownerWorkerId(),
                new LiveManagementController.AssignmentCommand("ASSIGN-GATEWAY-AUDIT-001"));

        ManagementAutonomyService replacement = new ManagementAutonomyService(new FileManagementStateStore(state));
        LiveManagementController afterRestart = new LiveManagementController(replacement);
        LiveManagementController.ObjectiveView recovered = afterRestart.objective(objective.objectiveId());

        assertEquals("WORKER-GATEWAY-DIRECTOR-001", recovered.objective().ownerWorkerId());
        assertTrue(recovered.objective().assignmentRefs().contains("ASSIGN-GATEWAY-AUDIT-001"));
        assertTrue(recovered.history().stream().anyMatch(e ->
                e.type() == ManagementAutonomyService.ManagementEvent.Type.OBJECTIVE_ACCEPTED
                        && e.actorWorkerId().equals("HUMAN-FOUNDER-001")
                        && e.detail().contains("AUTHORIZATION-GATEWAY-V2-001")));
        assertTrue(recovered.history().stream().anyMatch(e ->
                e.type() == ManagementAutonomyService.ManagementEvent.Type.STAFFING_NEED_DETECTED));
    }

    @Test
    void spoofedInitiatorIsRejected() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        LiveManagementController controller = new LiveManagementController(management);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                controller.appointObjective("HUMAN-OTHER",
                        new LiveManagementController.ObjectiveCommand("OBJ-1", "WORKER-DIRECTOR", "ORG-1", "work",
                                "HUMAN-FOUNDER-001", "AUTH-1", "AUTHZ-1")));
    }

    @Test
    void nonOwnerCannotOperateDelegatedObjective() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        LiveManagementController controller = new LiveManagementController(management);
        ManagementObjective objective = controller.appointObjective("HUMAN-FOUNDER-001",
                new LiveManagementController.ObjectiveCommand("OBJ-2", "WORKER-DIRECTOR", "ORG-1", "work",
                        "HUMAN-FOUNDER-001", "AUTH-1", "AUTHZ-1"));
        assertThrows(SecurityException.class, () -> controller.assessCapacity(objective.objectiveId(),
                "WORKER-OTHER", new LiveManagementController.CapacityCommand("gateway-engineering", 1, 0)));
    }
}
