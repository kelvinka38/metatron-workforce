package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.AnthropicLlmProviderClient;
import com.metatron.workforce.interaction.llm.GoogleLlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
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
 * Provider transports, capacity telemetry and model selection are owned here inside
 * the Intelligence domain. Human interaction, Workforce planning and Cognitive
 * Workers consume Intelligence; they do not own provider routers.
 */
public final class InstitutionalIntelligenceRuntime {
    private final List<LlmProvider> configuredProviders;
    private final FrontierSemanticInterpreter semanticInterpreter;
    private final DefaultToolFabric toolFabric;
    private final IntelligenceFabric fabric;

    public InstitutionalIntelligenceRuntime(
            String openAiApiKey,
            String googleApiKey,
            String anthropicApiKey,
            String openAiModel,
            String googleModel,
            String anthropicModel,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
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
        this.configuredProviders = List.copyOf(providers);

        Function<LlmProvider, String> modelSelector = provider -> switch (provider) {
            case OPENAI -> model(openAiModel, "gpt-4.1-mini");
            case GOOGLE -> model(googleModel, "gemini-3.7-flash");
            case ANTHROPIC -> model(anthropicModel, "claude-sonnet-4-20250514");
        };
        LlmProviderRouter router = new LlmProviderRouter(clients);
        this.semanticInterpreter = new FrontierSemanticInterpreter(
                router, modelSelector, configuredProviders, objectMapper);
        this.toolFabric = new DefaultToolFabric(List.of(
                new CurrentTimeToolAdapter(), new WebSearchToolAdapter()));
        RouterBackedIntelligenceEngine engine = new RouterBackedIntelligenceEngine(router, modelSelector);
        MultiModelDeliberationCoordinator deliberation = new MultiModelDeliberationCoordinator(
                engine, toolFabric, objectMapper);
        this.fabric = new IntelligenceFabric(
                new IntelligencePlanner(new AdaptiveProviderRoutingPolicy(configuredProviders, router.telemetry())),
                engine,
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance(),
                toolFabric,
                deliberation);
    }

    public List<LlmProvider> configuredProviders() { return configuredProviders; }
    public FrontierSemanticInterpreter semanticInterpreter() { return semanticInterpreter; }
    public DefaultToolFabric toolFabric() { return toolFabric; }
    public IntelligenceFabric fabric() { return fabric; }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String model(String configured, String fallback) {
        return present(configured) ? configured.trim() : fallback;
    }
}
