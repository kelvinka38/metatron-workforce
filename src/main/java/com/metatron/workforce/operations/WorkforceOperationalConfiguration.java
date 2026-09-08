package com.metatron.workforce.operations;

import com.metatron.workforce.phase5.*;
import com.metatron.workforce.phase7.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkforceOperationalConfiguration {
    @Bean WorkScheduleStateStore workScheduleStateStore(
            @Value("${METATRON_WORKFORCE_SCHEDULE_STATE_PATH:/var/lib/metatron-workforce/work-schedules.json}") String configured) {
        return new FileWorkScheduleStateStore(Path.of(configured));
    }
    @Bean StaffingStateStore staffingStateStore(
            @Value("${METATRON_WORKFORCE_STAFFING_STATE_PATH:/var/lib/metatron-workforce/staffing-state.json}") String configured) {
        return new FileStaffingStateStore(Path.of(configured));
    }
    @Bean ReviewStateStore reviewStateStore(
            @Value("${METATRON_WORKFORCE_REVIEW_STATE_PATH:/var/lib/metatron-workforce/review-state.json}") String configured) {
        return new FileReviewStateStore(Path.of(configured));
    }
    @Bean WorkScheduleService workScheduleService(WorkScheduleStateStore store) { return new WorkScheduleService(store); }
    @Bean StaffingService staffingService(StaffingStateStore store) { return new StaffingService(store); }
    @Bean ReviewService reviewService(ReviewStateStore store) { return new ReviewService(store); }
}
