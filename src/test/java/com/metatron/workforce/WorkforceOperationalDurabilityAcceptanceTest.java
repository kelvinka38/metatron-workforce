package com.metatron.workforce;

import com.metatron.workforce.phase5.*;
import com.metatron.workforce.phase7.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkforceOperationalDurabilityAcceptanceTest {
    @TempDir Path temp;

    @Test void scheduleStaffingAndReviewSurviveServiceReplacement() {
        Instant now = Instant.now();

        Path scheduleFile = temp.resolve("schedules.json");
        WorkScheduleService schedules = new WorkScheduleService(new FileWorkScheduleStateStore(scheduleFile));
        schedules.schedule(new WorkSchedule("S1", "A1", "W1", now.plusSeconds(60), now.plusSeconds(3600), .5,
                WorkSchedule.Status.PLANNED, "E-SCHEDULE"), 1.0);
        WorkScheduleService schedulesAfterRestart = new WorkScheduleService(new FileWorkScheduleStateStore(scheduleFile));
        assertEquals("A1", schedulesAfterRestart.get("S1").assignmentRef());

        Path staffingFile = temp.resolve("staffing.json");
        StaffingService staffing = new StaffingService(new FileStaffingStateStore(staffingFile));
        staffing.detect("ST1", "OBJ1", "ORG1", "W1", "engineering", 2, 1, now);
        staffing.propose("ST1", "PROPOSAL1", now.plusSeconds(1));
        StaffingService staffingAfterRestart = new StaffingService(new FileStaffingStateStore(staffingFile));
        assertEquals(StaffingRequest.Status.PROPOSED, staffingAfterRestart.get("ST1").status());

        Path reviewFile = temp.resolve("reviews.json");
        ReviewService reviews = new ReviewService(new FileReviewStateStore(reviewFile));
        reviews.record("R1", "REPORT1", "W1", "ROLE1", "AUTHORITY1", InstitutionalReview.Decision.APPROVED,
                "supported", List.of("E1"), now);
        ReviewService reviewsAfterRestart = new ReviewService(new FileReviewStateStore(reviewFile));
        assertEquals(InstitutionalReview.Decision.APPROVED, reviewsAfterRestart.get("R1").decision());
    }
}
