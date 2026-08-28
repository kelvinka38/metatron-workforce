package com.metatron.workforce.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkforceCoreConfiguration {
    @Bean
    WorkforceCoreStateStore workforceCoreStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_WORKFORCE_CORE_STATE_PATH",
                "/var/lib/metatron-workforce/workforce-core-state.json");
        return new FileWorkforceCoreStateStore(Path.of(configured));
    }

    @Bean
    WorkforceCoreService workforceCoreService(WorkforceCoreStateStore store) {
        return new WorkforceCoreService(store);
    }
}
