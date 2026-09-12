package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.actor.ActorScopedWorkerIntelligenceService;
import com.metatron.workforce.actor.WorkerActorRuntime;
import com.metatron.workforce.deliberation.DeliberatingWorkerIntelligenceService;
import com.metatron.workforce.deliberation.WorkerDeliberationRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/** Production composition for shared provider-neutral Intelligence plus durable Case/depth/cognitive state. */
@Configuration
public class IntelligenceRuntimeConfiguration {
    @Bean
    CognitiveArtifactStore cognitiveArtifactStore(
            ObjectMapper objectMapper,
            @Value("${METATRON_COGNITIVE_ARTIFACT_PATH:/var/lib/metatron-workforce/cognitive-artifacts}") String configured) {
        return new PersistentCognitiveArtifactStore(Path.of(configured), objectMapper);
    }

    @Bean
    InferenceConsumptionLedger inferenceConsumptionLedger() {
        return new InMemoryInferenceConsumptionLedger();
    }

    @Bean
    InstitutionalIntelligenceRuntime institutionalIntelligenceRuntime(
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${GEMINI_API_KEY:}") String googleApiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${OPENAI_MODEL:}") String openAiModel,
            @Value("${GEMINI_MODEL:}") String googleModel,
            @Value("${ANTHROPIC_MODEL:}") String anthropicModel,
            @Value("${METATRON_COGNITION_URL:}") String cognitionUrl,
            @Value("${METATRON_COGNITION_AUTH:}") String cognitionAuth,
            ObjectMapper objectMapper,
            CognitiveArtifactStore artifactStore,
            InferenceConsumptionLedger inferenceLedger) {
        MetatronCognitionClient cognitionClient = cognitionUrl == null || cognitionUrl.isBlank()
                ? null
                : new HttpMetatronCognitionClient(cognitionUrl, cognitionAuth, objectMapper);
        return new InstitutionalIntelligenceRuntime(
                openAiApiKey, googleApiKey, anthropicApiKey,
                openAiModel, googleModel, anthropicModel, objectMapper, artifactStore,
                cognitionClient, inferenceLedger);
    }

    @Bean
    IntelligenceFabric intelligenceFabric(InstitutionalIntelligenceRuntime runtime) {
        return runtime.fabric();
    }

    @Bean
    WorkerDeliberationRuntime workerDeliberationRuntime(
            ObjectMapper objectMapper,
            @Value("${METATRON_WORKER_DELIBERATION_PATH:/var/lib/metatron-workforce/worker-deliberation.json}") String configured) {
        return new WorkerDeliberationRuntime(Path.of(configured), objectMapper);
    }

    @Bean
    WorkerIntelligenceService workerIntelligenceService(
            InstitutionalIntelligenceRuntime runtime,
            WorkerActorRuntime actorRuntime,
            WorkerDeliberationRuntime deliberationRuntime) {
        WorkerIntelligenceService providerBacked = WorkerIntelligenceService.backedBy(
                runtime.fabric(), runtime.configuredProviders().size());
        WorkerIntelligenceService deliberating = new DeliberatingWorkerIntelligenceService(providerBacked, deliberationRuntime);
        return new ActorScopedWorkerIntelligenceService(deliberating, actorRuntime);
    }

    @Bean
    CognitionNeedGate cognitionNeedGate() {
        return new CognitionNeedGate();
    }

    @Bean
    InstitutionalContextResolver institutionalContextResolver() {
        return new InstitutionalContextResolver.Default();
    }

    @Bean
    IntelligenceRoutingFeedbackStore intelligenceRoutingFeedbackStore(
            ObjectMapper objectMapper,
            @Value("${METATRON_INTELLIGENCE_ROUTING_FEEDBACK_PATH:/var/lib/metatron-workforce/intelligence-routing-feedback.json}") String configured) {
        return new FileIntelligenceRoutingFeedbackStore(Path.of(configured), objectMapper);
    }

    @Bean
    IntelligenceRoutingFeedbackService intelligenceRoutingFeedbackService(
            InstitutionalIntelligenceRuntime runtime,
            IntelligenceRoutingFeedbackStore store) {
        return new IntelligenceRoutingFeedbackService(
                runtime.qualityRegistry(), runtime.router().callTrace(), store);
    }

    @Bean IntelligenceCaseStore intelligenceCaseStore(
            ObjectMapper objectMapper,
            @Value("${METATRON_INTELLIGENCE_CASE_PATH:/var/lib/metatron-workforce/intelligence-cases}") String configured) {
        return new PersistentIntelligenceCaseStore(Path.of(configured),objectMapper);
    }
    @Bean IntelligenceDepthPreferenceStore intelligenceDepthPreferenceStore(
            @Value("${METATRON_INTELLIGENCE_DEPTH_PATH:/var/lib/metatron-workforce/intelligence-depth}") String configured) {
        return new PersistentIntelligenceDepthPreferenceStore(Path.of(configured));
    }
    @Bean IntelligenceDepthControlService intelligenceDepthControlService(IntelligenceDepthPreferenceStore store) {
        return new IntelligenceDepthControlService(store);
    }
    @Bean IntelligenceCaseLifecycleService intelligenceCaseLifecycleService(
            IntelligenceCaseStore store,
            IntelligenceRoutingFeedbackService routingFeedback) {
        return new IntelligenceCaseLifecycleService(store, Clock.systemUTC(), routingFeedback);
    }
}
