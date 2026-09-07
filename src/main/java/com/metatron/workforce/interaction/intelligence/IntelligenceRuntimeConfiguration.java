package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/** Production composition for shared provider-neutral Intelligence plus durable Case/depth state. */
@Configuration
public class IntelligenceRuntimeConfiguration {
    @Bean
    InstitutionalIntelligenceRuntime institutionalIntelligenceRuntime(
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${GEMINI_API_KEY:}") String googleApiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${OPENAI_MODEL:}") String openAiModel,
            @Value("${GEMINI_MODEL:}") String googleModel,
            @Value("${ANTHROPIC_MODEL:}") String anthropicModel,
            ObjectMapper objectMapper) {
        return new InstitutionalIntelligenceRuntime(
                openAiApiKey, googleApiKey, anthropicApiKey,
                openAiModel, googleModel, anthropicModel, objectMapper);
    }

    @Bean
    IntelligenceFabric intelligenceFabric(InstitutionalIntelligenceRuntime runtime) {
        return runtime.fabric();
    }

    @Bean
    WorkerIntelligenceService workerIntelligenceService(InstitutionalIntelligenceRuntime runtime) {
        return WorkerIntelligenceService.backedBy(
                runtime.fabric(), runtime.configuredProviders().size());
    }

    @Bean IntelligenceCaseStore intelligenceCaseStore(ObjectMapper objectMapper) {
        String configured=System.getenv().getOrDefault("METATRON_INTELLIGENCE_CASE_PATH","/var/lib/metatron-workforce/intelligence-cases");
        return new PersistentIntelligenceCaseStore(Path.of(configured),objectMapper);
    }
    @Bean IntelligenceDepthPreferenceStore intelligenceDepthPreferenceStore() {
        String configured=System.getenv().getOrDefault("METATRON_INTELLIGENCE_DEPTH_PATH","/var/lib/metatron-workforce/intelligence-depth");
        return new PersistentIntelligenceDepthPreferenceStore(Path.of(configured));
    }
    @Bean IntelligenceDepthControlService intelligenceDepthControlService(IntelligenceDepthPreferenceStore store) {
        return new IntelligenceDepthControlService(store);
    }
    @Bean IntelligenceCaseLifecycleService intelligenceCaseLifecycleService(IntelligenceCaseStore store) {
        return new IntelligenceCaseLifecycleService(store,Clock.systemUTC());
    }
}
