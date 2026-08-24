package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.CapacityAwareRoutingPolicy;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.EvidenceBackedGovernance;
import com.metatron.workforce.interaction.intelligence.IntelligenceFabric;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.IntelligencePlanner;
import com.metatron.workforce.interaction.intelligence.IntelligenceRequest;
import com.metatron.workforce.interaction.intelligence.ProviderCapacity;
import com.metatron.workforce.interaction.intelligence.RouterBackedIntelligenceEngine;
import com.metatron.workforce.interaction.llm.AnthropicLlmProviderClient;
import com.metatron.workforce.interaction.llm.GoogleLlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.OpenAiLlmProviderClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Natural-language response path through the canonical Intelligence Fabric. */
public final class TelegramIntelligenceResponder {
    private static final String SYSTEM_CONTEXT = """
            You are Metatron Workforce's intelligence layer.
            Answer the human directly and naturally.
            Preserve the user's language; Vietnamese is preferred when the user writes Vietnamese.
            Do not claim that an action, audit, deployment, tool call, or external lookup happened unless the Workforce actually supplied evidence of it.
            When a request requires tools or execution that are not connected to this conversation path, say so plainly instead of fabricating completion.
            Keep ordinary answers concise unless the user asks for depth.
            """;

    private final IntelligenceFabric fabric;
    private final String configuredProvider;

    public TelegramIntelligenceResponder(
            String openAiApiKey,
            String googleApiKey,
            String anthropicApiKey,
            String provider,
            String openAiModel,
            String googleModel,
            String anthropicModel,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        HttpClient httpClient = HttpClient.newBuilder().build();
        List<LlmProviderClient> clients = new ArrayList<>();
        List<ProviderCapacity> capacities = new ArrayList<>();

        if (present(openAiApiKey)) {
            clients.add(new OpenAiLlmProviderClient(openAiApiKey, httpClient, objectMapper));
            capacities.add(new ProviderCapacity(LlmProvider.OPENAI, true, 100, 1, 100_000, 500, 1));
        }
        if (present(googleApiKey)) {
            clients.add(new GoogleLlmProviderClient(googleApiKey, httpClient, objectMapper));
            capacities.add(new ProviderCapacity(LlmProvider.GOOGLE, true, 90, 1, 100_000, 500, 1));
        }
        if (present(anthropicApiKey)) {
            clients.add(new AnthropicLlmProviderClient(anthropicApiKey, httpClient, objectMapper));
            capacities.add(new ProviderCapacity(LlmProvider.ANTHROPIC, true, 80, 1, 100_000, 700, 1));
        }

        this.configuredProvider = normalizeProvider(provider);
        Function<LlmProvider, String> modelSelector = modelSelector(openAiModel, googleModel, anthropicModel);
        LlmProviderRouter router = new LlmProviderRouter(clients);
        RouterBackedIntelligenceEngine engine = new RouterBackedIntelligenceEngine(router, modelSelector);
        this.fabric = new IntelligenceFabric(
                new IntelligencePlanner(new CapacityAwareRoutingPolicy(capacities)),
                engine,
                (request, responses) -> responses.getFirst().text(),
                new EvidenceBackedGovernance()
        );
    }

    public String respond(String senderId, String text) {
        return respond(senderId, text, "telegram-message");
    }

    public String respond(String senderId, String text, String externalMessageReference) {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(externalMessageReference, "externalMessageReference");
        LlmProvider requested = configuredProvider.isBlank() ? null : LlmProvider.valueOf(configuredProvider);
        List<LlmProvider> requestedProviders = requested == null ? List.of() : List.of(requested);

        IntelligenceRequest request = new IntelligenceRequest(
                "telegram-" + senderId + "-" + System.nanoTime(),
                "telegram:" + senderId,
                IntelligenceMode.REASONING,
                CollaborationMode.SINGLE,
                text,
                SYSTEM_CONTEXT + "\nThe current inbound channel is Telegram.\n",
                List.of("telegram:" + externalMessageReference),
                "analysis",
                "LOW",
                "interactive",
                "standard",
                "telegram-human",
                "direct natural-language answer",
                requestedProviders,
                1
        );
        return fabric.execute(request).text();
    }

    private static Function<LlmProvider, String> modelSelector(
            String openAiModel,
            String googleModel,
            String anthropicModel) {
        return provider -> switch (provider) {
            case OPENAI -> defaultModel(openAiModel, "gpt-4.1-mini");
            case GOOGLE -> defaultModel(googleModel, "gemini-2.5-flash");
            case ANTHROPIC -> defaultModel(anthropicModel, "claude-sonnet-4-20250514");
        };
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank() || "AUTO".equalsIgnoreCase(provider)) return "";
        return provider.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultModel(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
