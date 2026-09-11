package com.metatron.workforce.interaction.llm;

import com.metatron.workforce.interaction.intelligence.AdaptiveProviderRoutingPolicy;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.IntelligenceRequest;
import com.metatron.workforce.interaction.intelligence.ProviderCapabilityQualityRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CostAwareProviderRoutingTest {
    @Test
    void lowCostAutoRoutingPrefersMeasuredLowerCostWhenHealthAndQualityAreEqual() {
        ProviderTelemetryRegistry telemetry = new ProviderTelemetryRegistry(Map.of(
                LlmProvider.GOOGLE, new ProviderTelemetryRegistry.Pricing(new BigDecimal("5"), new BigDecimal("5")),
                LlmProvider.OPENAI, new ProviderTelemetryRegistry.Pricing(new BigDecimal("1"), new BigDecimal("1"))));

        long google = telemetry.begin(LlmProvider.GOOGLE);
        telemetry.success(LlmProvider.GOOGLE, google,
                new LlmResponse(LlmProvider.GOOGLE, "google-model", "ok", "g",
                        new LlmUsage(100, 100, 200), Map.of()));
        long openAi = telemetry.begin(LlmProvider.OPENAI);
        telemetry.success(LlmProvider.OPENAI, openAi,
                new LlmResponse(LlmProvider.OPENAI, "open-model", "ok", "o",
                        new LlmUsage(100, 100, 200), Map.of()));

        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry();
        quality.record(LlmProvider.GOOGLE, "analysis", 0.8, "eval:equal-quality-google");
        quality.record(LlmProvider.OPENAI, "analysis", 0.8, "eval:equal-quality-openai");

        AdaptiveProviderRoutingPolicy routing = new AdaptiveProviderRoutingPolicy(
                List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI), telemetry, quality);

        IntelligenceRequest request = new IntelligenceRequest(
                "cost-routing", "WORKER-ANALYST", IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                "Analyze economically", "context", List.of(), "analysis", "LOW",
                "work-runtime", "low-cost", "authority:test", "answer", List.of(), 1, false);

        assertEquals(List.of(LlmProvider.OPENAI), routing.select(request));
    }
}
