package com.metatron.workforce.workplace;

import com.metatron.workforce.management.AutonomySafetyService;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.management.ManagementObjective;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkplaceFounderControlServiceTest {
    @Test
    void founderWorkplaceSessionCanPauseUsingServerSideDurableAuthorityProvenance() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptObjective("OBJ-1", "WORKER-1", "organization:metatron",
                "Operate one governed Objective", Instant.now());

        WorkplaceContinuityService continuity = new WorkplaceContinuityService(
                new InMemoryWorkplaceContinuityStateStore(), management, List.of(), List.of());
        continuity.bindAcceptedObjective(
                "OBJ-1", "human-primary", "conversation:1", "telegram", "telegram:update:1",
                "workplace-request-admission:human-primary:metatron", Instant.now());

        AutonomySafetyService safety = new AutonomySafetyService();
        WorkplaceFounderControlService service = new WorkplaceFounderControlService(
                management, safety, null, continuity);

        var result = service.pause("OBJ-1");

        assertEquals(ManagementObjective.Status.PAUSED, result.objective().status());
        assertEquals("PAUSED", result.safety().controlStatus().name());
    }
}
