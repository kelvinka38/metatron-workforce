package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/** Production composition for durable Intelligence Case and Human depth-contract state. */
@Configuration
public class IntelligenceRuntimeConfiguration {
    @Bean
    IntelligenceCaseStore intelligenceCaseStore(ObjectMapper objectMapper) {
        String configured = System.getenv().getOrDefault(
                "METATRON_INTELLIGENCE_CASE_PATH",
                "/var/lib/metatron-workforce/intelligence-cases");
        return new PersistentIntelligenceCaseStore(Path.of(configured), objectMapper);
    }

    @Bean
    IntelligenceDepthPreferenceStore intelligenceDepthPreferenceStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_INTELLIGENCE_DEPTH_PATH",
                "/var/lib/metatron-workforce/intelligence-depth");
        return new PersistentIntelligenceDepthPreferenceStore(Path.of(configured));
    }

    @Bean
    IntelligenceCaseLifecycleService intelligenceCaseLifecycleService(IntelligenceCaseStore store) {
        return new IntelligenceCaseLifecycleService(store, Clock.systemUTC());
    }
}
