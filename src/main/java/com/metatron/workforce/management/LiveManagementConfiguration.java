package com.metatron.workforce.management;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Production composition for persistent Workforce management state. */
@Configuration
public class LiveManagementConfiguration {
    @Bean
    ManagementStateStore managementStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_MANAGEMENT_STATE_PATH",
                "/var/lib/metatron-workforce/management-state.json");
        return new FileManagementStateStore(Path.of(configured));
    }

    @Bean
    ManagementAutonomyService managementAutonomyService(ManagementStateStore store) {
        return new ManagementAutonomyService(store);
    }
}
