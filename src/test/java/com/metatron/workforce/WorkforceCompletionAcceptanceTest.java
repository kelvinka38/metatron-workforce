package com.metatron.workforce;

import com.metatron.workforce.core.*;
import com.metatron.workforce.phase5.*;
import com.metatron.workforce.phase7.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkforceCompletionAcceptanceTest {
    @TempDir Path temp;

    @Test void canonicalWorkerContinuityScheduleStaffingAndReviewAreExecutable() {
        Path state = temp.resolve("core.json");
        WorkforceCoreService first = new WorkforceCoreService(new FileWorkforceCoreStateStore(state));
        first.recognizeParticipant("P-DIR", WorkforceCoreService.ParticipantType.AI, "ADMISSION-EVIDENCE");
        first.admitWorker("W-DIR", "P-DIR");
        first.participate("PART-DIR", "W-DIR", "ORG-GATEWAY", "POSITION-DIRECTOR", "ROLE-DIRECTOR");
        first.attestCapability("W-DIR", "gateway-management", 1, "CAP-EVIDENCE");
        first.attestQualification("W-DIR", "gateway-director", "QUAL-EVIDENCE", Instant.now().plusSeconds(3600));
        first.setAvailability("W-DIR", true, 1);
        first.assign("ASSIGN-1", "OBJ-GATEWAY-V2", "W-DIR", "PART-DIR", "AUTHORITY-1", "AUTHZ-1", "deliver objective");

        WorkforceCoreService afterRestart = new WorkforceCoreService(new FileWorkforceCoreStateStore(state));
        assertEquals("P-DIR", afterRestart.worker("W-DIR").participantId());
        assertEquals(1, afterRestart.assignments("W-DIR").size());
        assertEquals(1, afterRestart.capabilities("W-DIR").size());

        Instant start = Instant.now().plusSeconds(60);
        WorkScheduleService schedules = new WorkScheduleService();
        schedules.schedule(new WorkSchedule("S1", "ASSIGN-1", "W-DIR", start, start.plusSeconds(3600), .7,
                WorkSchedule.Status.PLANNED, "SCHEDULE-EVIDENCE"), 1.0);
        assertThrows(IllegalStateException.class, () -> schedules.schedule(
                new WorkSchedule("S2", "ASSIGN-2", "W-DIR", start.plusSeconds(10), start.plusSeconds(1800), .4,
                        WorkSchedule.Status.PLANNED, "SCHEDULE-EVIDENCE-2"), 1.0));

        StaffingService staffing = new StaffingService();
        StaffingRequest need = staffing.detect("STAFF-1", "OBJ-GATEWAY-V2", "ORG-GATEWAY", "W-DIR",
                "gateway-engineering", 2, 0, Instant.now());
        assertEquals(2, need.gap());
        staffing.propose("STAFF-1", "PROPOSAL-STAFF-1", Instant.now());
        assertEquals(StaffingRequest.Status.RESOLVED, staffing.resolve("STAFF-1", "PART-WORKER-NEW", Instant.now()).status());

        ReviewService reviews = new ReviewService();
        InstitutionalReview review = reviews.record("REV-1", "REPORT-1", "W-DIR", "ROLE-DIRECTOR", "AUTHORITY-1",
                InstitutionalReview.Decision.APPROVED, "evidence supports completion", List.of("EVIDENCE-1"), Instant.now());
        assertEquals(InstitutionalReview.Decision.APPROVED, review.decision());

        afterRestart.setWorkerStatus("W-DIR", WorkforceCoreService.WorkerStatus.SUSPENDED);
        assertThrows(IllegalStateException.class, () -> afterRestart.assign("ASSIGN-2", "OBJ-2", "W-DIR", "PART-DIR",
                "AUTHORITY-1", "AUTHZ-2", "must be denied because worker suspended"));
    }
}
