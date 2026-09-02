package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.interaction.llm.AnthropicLlmProviderClient;
import com.metatron.workforce.interaction.llm.GoogleLlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.OpenAiLlmProviderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Production composition for general Worker runtime profiles, Objective workspaces, sandbox and cognition. */
@Configuration
public class GeneralExecutionRuntimeConfiguration {

    @Bean
    WorkerRuntimeProfileBindingService workerRuntimeProfileBindingService(
            @Value("${METATRON_RUNTIME_PROFILE_BINDINGS_PATH:/var/lib/metatron-workforce/runtime-profile-bindings.tsv}") String path) {
        return new WorkerRuntimeProfileBindingService(Path.of(path));
    }

    @Bean
    ObjectiveWorkspaceService objectiveWorkspaceService(
            @Value("${METATRON_OBJECTIVE_WORKSPACE_ROOT:/var/lib/metatron-workforce/objective-workspaces}") String root) {
        return new ObjectiveWorkspaceService(Path.of(root));
    }

    @Bean
    WorkerExecutionSandboxService workerExecutionSandboxService(
            @Value("${METATRON_SANDBOX_URL:http://workforce-sandbox:8090}") String endpoint,
            @Value("${METATRON_SANDBOX_TOKEN:}") String token,
            WorkerRuntimeProfileBindingService profiles,
            ObjectiveWorkspaceService workspaces,
            ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return new WorkerExecutionSandboxService(http, URI.create(endpoint), token, profiles, workspaces, json);
    }

    @Bean
    GeneralWorkspaceActionCatalog generalWorkspaceActionCatalog(
            ObjectiveWorkspaceService workspaces,
            WorkerExecutionSandboxService sandbox,
            WorkerRuntimeProfileBindingService profiles,
            ObjectMapper json) {
        return new GeneralWorkspaceActionCatalog(workspaces, sandbox, profiles, json);
    }

    @Bean
    GeneralCognitiveWorkerBrainFactory generalCognitiveWorkerBrainFactory(
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${GEMINI_API_KEY:}") String googleApiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${METATRON_WORKER_LLM_PROVIDER:}") String workerProvider,
            @Value("${METATRON_LLM_PROVIDER:AUTO}") String defaultProvider,
            @Value("${OPENAI_MODEL:gpt-4.1-mini}") String openAiModel,
            @Value("${GEMINI_MODEL:gemini-3.7-flash}") String googleModel,
            @Value("${ANTHROPIC_MODEL:claude-sonnet-4-20250514}") String anthropicModel,
            ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_2).build();
        List<LlmProviderClient> clients = new ArrayList<>();
        if (present(openAiApiKey)) clients.add(new OpenAiLlmProviderClient(openAiApiKey, http, json));
        if (present(googleApiKey)) clients.add(new GoogleLlmProviderClient(googleApiKey, http, json));
        if (present(anthropicApiKey)) clients.add(new AnthropicLlmProviderClient(anthropicApiKey, http, json));

        String configured = present(workerProvider) ? workerProvider : defaultProvider;
        LlmProvider selected = selectProvider(configured, openAiApiKey, googleApiKey, anthropicApiKey);
        String model = switch (selected) {
            case OPENAI -> model(openAiModel, "gpt-4.1-mini");
            case GOOGLE -> model(googleModel, "gemini-3.7-flash");
            case ANTHROPIC -> model(anthropicModel, "claude-sonnet-4-20250514");
        };
        return new GeneralCognitiveWorkerBrainFactory(new LlmProviderRouter(clients), selected, model, json);
    }

    private static LlmProvider selectProvider(String configured,
                                              String openAiKey,
                                              String googleKey,
                                              String anthropicKey) {
        String value = configured == null ? "AUTO" : configured.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() || value.equals("AUTO")) {
            if (present(googleKey)) return LlmProvider.GOOGLE;
            if (present(openAiKey)) return LlmProvider.OPENAI;
            if (present(anthropicKey)) return LlmProvider.ANTHROPIC;
            // Keep application bootable without provider credentials; actual general cognitive execution fails closed in the router.
            return LlmProvider.OPENAI;
        }
        return switch (value) {
            case "GOOGLE", "GEMINI" -> LlmProvider.GOOGLE;
            case "OPENAI", "GPT" -> LlmProvider.OPENAI;
            case "ANTHROPIC", "CLAUDE" -> LlmProvider.ANTHROPIC;
            default -> throw new IllegalArgumentException("unsupported worker LLM provider: " + configured);
        };
    }

    private static String model(String configured, String fallback) {
        return present(configured) ? configured.trim() : fallback;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
