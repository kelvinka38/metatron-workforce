package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.AnthropicLlmProviderClient;
import com.metatron.workforce.interaction.llm.GoogleLlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.OpenAiLlmProviderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Intelligence proposal service consumed asynchronously by Workforce management. */
@Configuration
public class ExecutionPlanningConfiguration {
    @Bean
    ExecutionPlanProposalService executionPlanProposalService(
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${GEMINI_API_KEY:}") String googleApiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${OPENAI_MODEL:}") String openAiModel,
            @Value("${GEMINI_MODEL:}") String googleModel,
            @Value("${ANTHROPIC_MODEL:}") String anthropicModel,
            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_2).build();
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
        Function<LlmProvider, String> modelSelector = provider -> switch (provider) {
            case OPENAI -> defaultModel(openAiModel, "gpt-4.1-mini");
            case GOOGLE -> defaultModel(googleModel, "gemini-3.7-flash");
            case ANTHROPIC -> defaultModel(anthropicModel, "claude-sonnet-4-20250514");
        };
        ExecutionPlanProposalService frontierPlanner = new ExecutionWorkPlanner(
                new LlmProviderRouter(clients), modelSelector, providers, objectMapper);
        return new GeneralActionComposingExecutionPlanProposalService(frontierPlanner);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String defaultModel(String configured, String fallback) {
        return present(configured) ? configured.trim() : fallback;
    }
}
