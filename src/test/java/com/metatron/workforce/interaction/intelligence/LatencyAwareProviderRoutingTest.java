package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.llm.ProviderTelemetryRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LatencyAwareProviderRoutingTest {
    @Test
    void interactiveAutoRoutingPrefersMeasuredLowerLatencyWhenHealthAndQualityAreEqual() throws Exception {
        ProviderTelemetryRegistry telemetry = new ProviderTelemetryRegistry();

        long google = telemetry.begin(LlmProvider.GOOGLE);
        Thread.sleep(30L);
        telemetry.success(LlmProvider.GOOGLE, google,
                new LlmResponse(LlmProvider.GOOGLE, "google-model", "ok", "google-latency"));

        long openAi = telemetry.begin(LlmProvider.OPENAI);
        Thread.sleep(2L);
        telemetry.success(LlmProvider.OPENAI, openAi,
                new LlmResponse(LlmProvider.OPENAI, "open-model", "ok", "open-latency"));

        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry();
        quality.record(LlmProvider.GOOGLE, "analysis", 0.8, "eval:equal-quality-google");
        quality.record(LlmProvider.OPENAI, "analysis", 0.8, "eval:equal-quality-openai");

        AdaptiveProviderRoutingPolicy routing = new AdaptiveProviderRoutingPolicy(
                List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI), telemetry, quality);

        IntelligenceRequest request = new IntelligenceRequest(
                "latency-routing", "WORKER-ANALYST", IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                "Analyze quickly", "context", List.of(), "analysis", "LOW",
                "interactive-fast", "standard", "authority:test", "answer", List.of(), 1, false);

        assertEquals(List.of(LlmProvider.OPENAI), routing.select(request));
    }
}
