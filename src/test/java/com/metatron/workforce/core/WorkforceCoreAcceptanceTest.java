package com.metatron.workforce.core;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class WorkforceCoreAcceptanceTest {
    @Test void participantToWorkerToParticipationToAssignmentIsExplicitAndAttributed() {
        WorkforceCoreService s = new WorkforceCoreService();
        s.recognizeParticipant("P1", WorkforceCoreService.ParticipantType.AI, "ADMISSION-1");
        assertThrows(IllegalStateException.class, () -> s.admitWorker("W-X", "UNKNOWN"));
        s.admitWorker("W1", "P1");
        s.participate("PART1", "W1", "ORG-GATEWAY", "POSITION-DIRECTOR", "ROLE-DIRECTOR");
        s.attestCapability("W1", "gateway-management", 1.0, "EVIDENCE-CAP-1");
        s.attestQualification("W1", "gateway-director", "EVIDENCE-QUAL-1", Instant.now().plusSeconds(3600));
        s.setAvailability("W1", true, 1.0);
        var a = s.assign("A1", "OBJ-GATEWAY-V2", "W1", "PART1", "AUTHORITY-1", "AUTHZ-1", "Lead Gateway V2");
        assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE, a.status());
        assertEquals("AUTHORITY-1", a.authorityRef());
        assertEquals("AUTHZ-1", a.authorizationRef());
        assertEquals(1, s.participations("W1").size());
        assertEquals(1, s.assignments("W1").size());
        assertTrue(s.availability("W1").orElseThrow().available());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED,
                s.transitionAssignment("A1", WorkforceCoreService.AssignmentStatus.COMPLETED).status());
        assertThrows(IllegalStateException.class, () -> s.transitionAssignment("A1", WorkforceCoreService.AssignmentStatus.ACTIVE));
    }
}
