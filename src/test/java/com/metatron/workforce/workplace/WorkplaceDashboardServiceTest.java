package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.work.WorkService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorkplaceDashboardServiceTest {
    @Test
    void dashboardReflectsActualWorkforceStateAndExceptions() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("P-DIR", WorkforceCoreService.ParticipantType.AI, "ADMISSION-1");
        core.admitWorker("W-DIR", "P-DIR");
        core.participate("PART-DIR", "W-DIR", "ORG-GATEWAY", "DIRECTOR", "GATEWAY-DIRECTOR");
        core.attestCapability("W-DIR", "gateway-management", 1.0, "E-CAP");
        core.setAvailability("W-DIR", true, 0.75);
        core.assign("A-1", "OBJ-1", "W-DIR", "PART-DIR", "AUTH-1", "AUTHZ-1", "Lead delivery");

        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptObjective("OBJ-1", "W-DIR", "ORG-GATEWAY", "Deliver Gateway V2", Instant.now());
        management.markBlocked("OBJ-1", "W-DIR", "dependency", Instant.now());

        WorkService work = new WorkService();
        work.originate("WORK-1", "OBJ-1", "ORG-GATEWAY", "W-DIR", "Audit Gateway", Instant.now());
        work.block("WORK-1", "E-BLOCK", Instant.now());

        var d = new WorkplaceDashboardService(core, management, work).dashboard();
        assertEquals(1, d.summary().workers());
        assertEquals(1, d.summary().objectives());
        assertEquals(1, d.summary().workItems());
        assertEquals(1, d.summary().assignments());
        assertTrue(d.summary().alerts() >= 2);
        assertEquals("W-DIR", d.workers().getFirst().workerId());
        assertEquals("BLOCKED", d.objectives().getFirst().status().name());
        assertEquals("BLOCKED", d.work().getFirst().status().name());
    }
}
