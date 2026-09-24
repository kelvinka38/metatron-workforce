package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Founder rule (2026-09-24): no LLM provider that requires paid credit. OpenAI and Anthropic are
 * credit-billed (production was already failing on them with 429 quota / 400 credit errors), so the
 * production Workforce container must not receive their credentials at all -- InstitutionalIntelligenceRuntime
 * only configures a provider whose key is present, so withholding the key removes the provider. Ollama
 * (self-hosted) and free-tier Gemini remain.
 */
class NoCreditBilledLlmProviderComposeTest {
    @Test
    void productionComposeNeverPassesCreditBilledLlmCredentialsToWorkforce() throws Exception {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"));
        assertFalse(compose.contains("OPENAI_API_KEY"), "OpenAI requires paid credit and must not be configured");
        assertFalse(compose.contains("ANTHROPIC_API_KEY"), "Anthropic requires paid credit and must not be configured");
        assertTrue(compose.contains("GEMINI_API_KEY: \"${GEMINI_API_KEY:-}\""), "free-tier Gemini remains optional");
    }
}
