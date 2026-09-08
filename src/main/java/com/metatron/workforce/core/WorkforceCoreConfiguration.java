package com.metatron.workforce.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkforceCoreConfiguration {
    @Bean
    WorkforceCoreStateStore workforceCoreStateStore(
            @Value("${METATRON_WORKFORCE_CORE_STATE_PATH:/var/lib/metatron-workforce/workforce-core-state.json}") String configured) {
        return new FileWorkforceCoreStateStore(Path.of(configured));
    }

    @Bean
    WorkforceCoreService workforceCoreService(WorkforceCoreStateStore store) {
        return new WorkforceCoreService(store);
    }
}
