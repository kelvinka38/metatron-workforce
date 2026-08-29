package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.ProviderTelemetryRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AdaptiveProviderRoutingPolicyTest {
    @Test
    void autoRoutingMovesAwayFromProviderInMeasuredRateLimitCooldown() {
        ProviderTelemetryRegistry telemetry = new ProviderTelemetryRegistry();
        long started = telemetry.begin(LlmProvider.GOOGLE);
        telemetry.failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));

        AdaptiveProviderRoutingPolicy policy = new AdaptiveProviderRoutingPolicy(
                List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC, LlmProvider.OPENAI), telemetry);

        assertEquals(List.of(LlmProvider.ANTHROPIC, LlmProvider.OPENAI),
                policy.select(request(List.of(), 2)));
    }

    @Test
    void explicitProviderRequestIsPreservedEvenWhenTelemetryPredictsFailure() {
        ProviderTelemetryRegistry telemetry = new ProviderTelemetryRegistry();
        long started = telemetry.begin(LlmProvider.GOOGLE);
        telemetry.failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));

        AdaptiveProviderRoutingPolicy policy = new AdaptiveProviderRoutingPolicy(
                List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC), telemetry);

        assertEquals(List.of(LlmProvider.GOOGLE),
                policy.select(request(List.of(LlmProvider.GOOGLE), 1)));
    }

    private static IntelligenceRequest request(List<LlmProvider> providers, int maxProviders) {
        return new IntelligenceRequest("req", "human:1", IntelligenceMode.REASONING,
                maxProviders > 1 ? CollaborationMode.INDEPENDENT_SECOND_OPINION : CollaborationMode.SINGLE,
                "analyze", "context", List.of(), "analysis", "MEDIUM", "interactive", "standard", "",
                "answer", providers, maxProviders, false);
    }
}
