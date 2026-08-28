package com.metatron.workforce.operations;

import com.metatron.workforce.phase5.*;
import com.metatron.workforce.phase7.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkforceOperationalConfiguration {
    @Bean WorkScheduleStateStore workScheduleStateStore() {
        return new FileWorkScheduleStateStore(Path.of(System.getenv().getOrDefault(
                "METATRON_WORKFORCE_SCHEDULE_STATE_PATH", "/var/lib/metatron-workforce/work-schedules.json")));
    }
    @Bean StaffingStateStore staffingStateStore() {
        return new FileStaffingStateStore(Path.of(System.getenv().getOrDefault(
                "METATRON_WORKFORCE_STAFFING_STATE_PATH", "/var/lib/metatron-workforce/staffing-state.json")));
    }
    @Bean ReviewStateStore reviewStateStore() {
        return new FileReviewStateStore(Path.of(System.getenv().getOrDefault(
                "METATRON_WORKFORCE_REVIEW_STATE_PATH", "/var/lib/metatron-workforce/review-state.json")));
    }
    @Bean WorkScheduleService workScheduleService(WorkScheduleStateStore store) { return new WorkScheduleService(store); }
    @Bean StaffingService staffingService(StaffingStateStore store) { return new StaffingService(store); }
    @Bean ReviewService reviewService(ReviewStateStore store) { return new ReviewService(store); }
}
