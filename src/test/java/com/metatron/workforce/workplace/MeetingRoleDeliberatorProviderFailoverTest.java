package com.metatron.workforce.workplace;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeetingRoleDeliberatorProviderFailoverTest {
    @Test
    void preferredProviderCreditFailureFallsBackToNextConfiguredProvider() {
        AtomicInteger openAiCalls = new AtomicInteger();
        AtomicInteger googleCalls = new AtomicInteger();
        LlmProviderClient openAi = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.OPENAI; }
            @Override public LlmResponse complete(LlmRequest request) {
                openAiCalls.incrementAndGet();
                throw new IllegalStateException("openai_request_failed:429:no credits");
            }
        };
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                googleCalls.incrementAndGet();
                return new LlmResponse(LlmProvider.GOOGLE, request.model(),
                        "Strategy assessment from fallback provider", "google://request-1");
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(openAi, google));
        MeetingRoleDeliberator deliberator = new MeetingRoleDeliberator.ProviderBacked(
                router,
                List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE),
                Map.of(LlmProvider.OPENAI, "gpt-test", LlmProvider.GOOGLE, "gemini-test"));

        MeetingRoleDeliberator.Deliberation result = deliberator.deliberate(
                "Head of Strategy", "growth plan", "Founder conversation");

        assertEquals(1, openAiCalls.get());
        assertEquals(1, googleCalls.get());
        assertEquals("Strategy assessment from fallback provider", result.text());
        assertTrue(result.providerReference().startsWith("provider:google:model:gemini-test:request:google://request-1"));
    }

    @Test
    void synthesisUsesSameProviderFailoverPolicy() {
        LlmProviderClient openAi = failing(LlmProvider.OPENAI, "openai_request_failed:429:no credits");
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.ANTHROPIC, request.model(),
                        "Preserved disagreement and recommendation", "anthropic://request-2");
            }
        };
        MeetingRoleDeliberator deliberator = new MeetingRoleDeliberator.ProviderBacked(
                new LlmProviderRouter(List.of(openAi, anthropic)),
                List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC),
                Map.of(LlmProvider.OPENAI, "gpt-test", LlmProvider.ANTHROPIC, "claude-test"));

        MeetingRoleDeliberator.Deliberation result = deliberator.synthesize(
                "growth plan",
                List.of(new MeetingRecord.Contribution("role:head-of-strategy", "Head of Strategy",
                        "Grow carefully", "provider:google:model:test:request:1")),
                "Founder conversation");

        assertEquals("Preserved disagreement and recommendation", result.text());
        assertTrue(result.providerReference().startsWith("provider:anthropic:model:claude-test"));
    }

    @Test
    void meetingFailsClosedOnlyAfterAllConfiguredProvidersFail() {
        MeetingRoleDeliberator deliberator = new MeetingRoleDeliberator.ProviderBacked(
                new LlmProviderRouter(List.of(
                        failing(LlmProvider.OPENAI, "openai_request_failed:429:no credits"),
                        failing(LlmProvider.GOOGLE, "google_request_failed:503:unavailable"))),
                List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE),
                Map.of(LlmProvider.OPENAI, "gpt-test", LlmProvider.GOOGLE, "gemini-test"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> deliberator.deliberate("Head of Finance", "growth plan", "context"));

        assertTrue(failure.getMessage().startsWith("meeting_provider_exhausted:"));
        assertTrue(failure.getMessage().contains("openai="));
        assertTrue(failure.getMessage().contains("google="));
    }

    private static LlmProviderClient failing(LlmProvider provider, String message) {
        return new LlmProviderClient() {
            @Override public LlmProvider provider() { return provider; }
            @Override public LlmResponse complete(LlmRequest request) { throw new IllegalStateException(message); }
        };
    }
}
