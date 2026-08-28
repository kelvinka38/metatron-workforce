package com.metatron.workforce.operations;

import com.metatron.workforce.phase5.StaffingService;
import com.metatron.workforce.phase5.WorkScheduleService;
import com.metatron.workforce.phase7.ReviewService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkforceOperationalConfiguration {
    @Bean WorkScheduleService workScheduleService() { return new WorkScheduleService(); }
    @Bean StaffingService staffingService() { return new StaffingService(); }
    @Bean ReviewService reviewService() { return new ReviewService(); }
}
