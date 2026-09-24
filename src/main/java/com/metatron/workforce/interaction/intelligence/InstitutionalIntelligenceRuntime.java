package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.AnthropicLlmProviderClient;
import com.metatron.workforce.interaction.llm.GoogleLlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.OllamaLlmProviderClient;
import com.metatron.workforce.interaction.llm.OpenAiLlmProviderClient;

import com.metatron.workforce.interaction.tools.CurrentTimeToolAdapter;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One shared institutional Intelligence runtime for every production consumer.
 *
 * Provider transports, measured capacity/quality telemetry, reusable cognitive state and model
 * routing are owned here inside the Intelligence domain. Worker identity never owns or pins an LLM.
 */
public final class InstitutionalIntelligenceRuntime {
    private final List<LlmProvider> configuredProviders;
    private final FrontierSemanticInterpreter semanticInterpreter;
    private final DefaultToolFabric toolFabric;
    private final IntelligenceFabric fabric;
    private final LlmProviderRouter router;
    private final ProviderCapabilityQualityRegistry qualityRegistry;
    private final AdaptiveModelRoutingPolicy modelRoutingPolicy;

    /** Backward-compatible composition using process-local cognitive artifacts. */
    public InstitutionalIntelligenceRuntime(
            String openAiApiKey,
            String googleApiKey,
            String anthropicApiKey,
            String openAiModel,
            String googleModel,
            String anthropicModel,
            ObjectMapper objectMapper) {
        this(openAiApiKey, googleApiKey, anthropicApiKey,
                openAiModel, googleModel, anthropicModel, objectMapper,
                new InMemoryCognitiveArtifactStore());
    }

    public InstitutionalIntelligenceRuntime(
            String openAiApiKey,
            String googleApiKey,
            String anthropicApiKey,
            String openAiModel,
            String googleModel,
            String anthropicModel,
            ObjectMapper objectMapper,
            CognitiveArtifactStore artifactStore) {
        this(openAiApiKey, googleApiKey, anthropicApiKey, openAiModel, googleModel, anthropicModel,
                objectMapper, artifactStore, null, new InMemoryInferenceConsumptionLedger());
    }

    /**
     * Backward-compatible composition predating the Ollama planning fallback (2026-09-16): callers
     * using this constructor get no Ollama fallback option, exactly as before -- this preserves prior
     * behavior for any caller that has not opted in, rather than silently changing what they get.
     */
    public InstitutionalIntelligenceRuntime(
            String openAiApiKey,
            String googleApiKey,
            String anthropicApiKey,
            String openAiModel,
            String googleModel,
            String anthropicModel,
            ObjectMapper objectMapper,
            CognitiveArtifactStore artifactStore,
            MetatronCognitionClient metatronCognitionClient,
            InferenceConsumptionLedger inferenceLedger) {
        this(openAiApiKey, googleApiKey, anthropicApiKey, openAiModel, googleModel, anthropicModel,
                "", "", objectMapper, artifactStore, metatronCognitionClient, inferenceLedger);
    }

    /**
     * Root-cause fix (2026-09-16): the planning layer previously had no self-hosted fallback at all --
     * see the class-level Javadoc on OllamaLlmProviderClient for the production incident that exposed
     * this. ollamaUrl/ollamaModel are optional (blank disables Ollama for planning, matching prior
     * behavior exactly); when present, Ollama is added as the LAST candidate provider, after the
     * configured paid providers, so normal planning still prefers the faster paid providers and only
     * falls back to the self-hosted model when all of them fail.
     */
    public InstitutionalIntelligenceRuntime(
            String openAiApiKey,
            String googleApiKey,
            String anthropicApiKey,
            String openAiModel,
            String googleModel,
            String anthropicModel,
            String ollamaUrl,
            String ollamaModel,
            ObjectMapper objectMapper,
            CognitiveArtifactStore artifactStore,
            MetatronCognitionClient metatronCognitionClient,
            InferenceConsumptionLedger inferenceLedger) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(artifactStore, "artifactStore");
        Objects.requireNonNull(inferenceLedger, "inferenceLedger");
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_2)
                .build();
        List<LlmProviderClient> clients = new ArrayList<>();
        List<LlmProvider> providers = new ArrayList<>();
        if (present(openAiApiKey)) {
            clients.add(new OpenAiLlmProviderClient(openAiApiKey, httpClient, objectMapper));
            providers.add(LlmProvider.OPENAI);
        }
        if (present(googleApiKey)) {
            clients.add(new GoogleLlmProviderClient(googleApiKey, httpClient, objectMapper));
            providers.add(LlmProvider.GOOGLE);
        }
        if (present(anthropicApiKey)) {
            clients.add(new AnthropicLlmProviderClient(anthropicApiKey, httpClient, objectMapper));
            providers.add(LlmProvider.ANTHROPIC);
        }
        if (present(ollamaUrl)) {
            HttpClient ollamaHttpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            clients.add(new OllamaLlmProviderClient(ollamaUrl, ollamaHttpClient, objectMapper));
            providers.add(LlmProvider.OLLAMA);
        }
        this.configuredProviders = List.copyOf(providers);

        String resolvedOllamaModel = present(ollamaModel) ? ollamaModel.trim() : "qwen3:4b";
        Function<LlmProvider, String> configuredDefaultModel = provider -> switch (provider) {
            case OPENAI -> model(openAiModel, "gpt-4.1-mini");
            case GOOGLE -> model(googleModel, "gemini-3.7-flash");
            case ANTHROPIC -> model(anthropicModel, "claude-sonnet-4-20250514");
            case OLLAMA -> resolvedOllamaModel;
        };

        this.modelRoutingPolicy = AdaptiveModelRoutingPolicy.fromEnvironment(configuredDefaultModel);
        this.qualityRegistry = new ProviderCapabilityQualityRegistry();
        this.router = new LlmProviderRouter(clients);
        this.semanticInterpreter = new FrontierSemanticInterpreter(
                router, configuredDefaultModel, configuredProviders, objectMapper);
        this.toolFabric = new DefaultToolFabric(List.of(
                new CurrentTimeToolAdapter(), new WebSearchToolAdapter()));
        RouterBackedIntelligenceEngine engine = new RouterBackedIntelligenceEngine(router, modelRoutingPolicy);
        MultiModelDeliberationCoordinator deliberation = new MultiModelDeliberationCoordinator(
                engine, toolFabric, objectMapper);
        this.fabric = new IntelligenceFabric(
                new IntelligencePlanner(new AdaptiveProviderRoutingPolicy(
                        configuredProviders, router.telemetry(), qualityRegistry)),
                engine,
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance(),
                toolFabric,
                deliberation,
                artifactStore,
                metatronCognitionClient,
                new CognitionAdmissionPolicy(),
                inferenceLedger);
    }

    public List<LlmProvider> configuredProviders() { return configuredProviders; }
    public FrontierSemanticInterpreter semanticInterpreter() { return semanticInterpreter; }
    public DefaultToolFabric toolFabric() { return toolFabric; }
    public IntelligenceFabric fabric() { return fabric; }
    public LlmProviderRouter router() { return router; }
    public ProviderCapabilityQualityRegistry qualityRegistry() { return qualityRegistry; }
    public AdaptiveModelRoutingPolicy modelRoutingPolicy() { return modelRoutingPolicy; }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String model(String configured, String fallback) {
        return present(configured) ? configured.trim() : fallback;
    }
}
