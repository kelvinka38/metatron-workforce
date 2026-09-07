package com.metatron.workforce.runtime;

import com.metatron.workforce.action.GeneralCognitiveWorkerBrain;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneralExecutionRuntimeConfigurationTest {
    @Test
    void preferredProviderIsFirstButConfiguredAlternatesRemainAvailable() {
        List<GeneralCognitiveWorkerBrain.ProviderRoute> routes =
                GeneralExecutionRuntimeConfiguration.providerRoutes(
                        LlmProvider.GOOGLE,
                        "openai-key", "google-key", "anthropic-key",
                        "gpt-test", "gemini-test", "claude-test");

        assertEquals(
                List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI, LlmProvider.ANTHROPIC),
                routes.stream().map(GeneralCognitiveWorkerBrain.ProviderRoute::provider).toList());
        assertEquals(List.of("gemini-test", "gpt-test", "claude-test"),
                routes.stream().map(GeneralCognitiveWorkerBrain.ProviderRoute::model).toList());
    }

    @Test
    void missingPreferredCredentialFallsBackToConfiguredProviders() {
        List<GeneralCognitiveWorkerBrain.ProviderRoute> routes =
                GeneralExecutionRuntimeConfiguration.providerRoutes(
                        LlmProvider.GOOGLE,
                        "openai-key", "", "anthropic-key",
                        "gpt-test", "gemini-test", "claude-test");

        assertEquals(
                List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC),
                routes.stream().map(GeneralCognitiveWorkerBrain.ProviderRoute::provider).toList());
    }

    @Test
    void noCredentialsKeepsApplicationBootableButExecutionRouteFailsClosedLater() {
        List<GeneralCognitiveWorkerBrain.ProviderRoute> routes =
                GeneralExecutionRuntimeConfiguration.providerRoutes(
                        LlmProvider.OPENAI,
                        "", "", "",
                        "gpt-test", "gemini-test", "claude-test");

        assertEquals(List.of(LlmProvider.OPENAI),
                routes.stream().map(GeneralCognitiveWorkerBrain.ProviderRoute::provider).toList());
    }
}
