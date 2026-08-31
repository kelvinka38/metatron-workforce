package com.metatron.workforce.workplace;

import com.metatron.workforce.management.ManagementAutonomyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

@Configuration
public class WorkplaceContinuityConfiguration {
    @Bean
    WorkplaceContinuityStateStore workplaceContinuityStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_WORKPLACE_CONTINUITY_STATE_PATH",
                "/var/lib/metatron-workforce/workplace-continuity-state.json");
        return new FileWorkplaceContinuityStateStore(Path.of(configured));
    }

    @Bean
    WorkplaceContinuityService workplaceContinuityService(
            WorkplaceContinuityStateStore store,
            ManagementAutonomyService management,
            List<WorkplaceChannelAuthorizationVerifier> authorizationVerifiers,
            List<WorkplaceDeliveryAdapter> deliveryAdapters) {
        return new WorkplaceContinuityService(store, management, authorizationVerifiers, deliveryAdapters);
    }
}
