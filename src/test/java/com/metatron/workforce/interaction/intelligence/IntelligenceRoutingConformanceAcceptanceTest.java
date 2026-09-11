package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.llm.ProviderTelemetryRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IntelligenceRoutingConformanceAcceptanceTest {
    @Test
    void measuredCapabilityQualityOverridesStaticProviderPriorityWithoutPinningWorkerIdentity() {
        ProviderTelemetryRegistry telemetry = new ProviderTelemetryRegistry();
        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry();
        quality.record(LlmProvider.OPENAI, "software.coding", 0.96, "eval:coding:openai:2026-09");
        quality.record(LlmProvider.ANTHROPIC, "software.coding", 0.88, "eval:coding:anthropic:2026-09");
        quality.record(LlmProvider.GOOGLE, "software.coding", 0.61, "eval:coding:google:2026-09");

        AdaptiveProviderRoutingPolicy routing = new AdaptiveProviderRoutingPolicy(
                List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC, LlmProvider.OPENAI), telemetry, quality);

        assertEquals(List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC),
                routing.select(request("WORKER-SOFTWARE-ENGINEER", "software.coding", "work-runtime", "bounded", 2)));
    }

    @Test
    void requestAwareModelRoutingUsesTierProfilesAndNeverInventsProviderIdentity() {
        AdaptiveModelRoutingPolicy models = new AdaptiveModelRoutingPolicy(Map.of(
                LlmProvider.OPENAI, new AdaptiveModelRoutingPolicy.ModelProfile("open-fast", "open-analyze", "open-deep"),
                LlmProvider.GOOGLE, new AdaptiveModelRoutingPolicy.ModelProfile("google-fast", "google-analyze", "google-deep"),
                LlmProvider.ANTHROPIC, new AdaptiveModelRoutingPolicy.ModelProfile("anthropic-fast", "anthropic-analyze", "anthropic-deep")));

        assertEquals("open-fast", models.select(LlmProvider.OPENAI,
                request("WORKER-1", "semantic-normalization", "fast", "standard", 1)));
        assertEquals("open-analyze", models.select(LlmProvider.OPENAI,
                request("WORKER-1", "software.coding", "work-runtime", "bounded", 1)));
        assertEquals("open-deep", models.select(LlmProvider.OPENAI,
                request("WORKER-1", "analysis", "extended", "deep", 1)));
    }

    @Test
    void providerFailureFallsThroughToNextRankedProviderWithSameWorkerAndRequestAwareModel() {
        ProviderTelemetryRegistry telemetry = new ProviderTelemetryRegistry();
        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry();
        quality.record(LlmProvider.OPENAI, "software.coding", 0.95, "eval:coding:openai");
        quality.record(LlmProvider.ANTHROPIC, "software.coding", 0.90, "eval:coding:anthropic");

        AtomicReference<LlmRequest> failedRequest = new AtomicReference<>();
        AtomicReference<LlmRequest> successfulRequest = new AtomicReference<>();
        LlmProviderClient openAi = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.OPENAI; }
            @Override public LlmResponse complete(LlmRequest request) {
                failedRequest.set(request);
                throw new IllegalStateException("simulated-provider-capacity-failure");
            }
        };
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                successfulRequest.set(request);
                return new LlmResponse(LlmProvider.ANTHROPIC, request.model(),
                        "fallback provider completed the coding analysis", "anthropic-request-1");
            }
        };

        LlmProviderRouter router = new LlmProviderRouter(List.of(openAi, anthropic), telemetry);
        AdaptiveModelRoutingPolicy models = new AdaptiveModelRoutingPolicy(Map.of(
                LlmProvider.OPENAI, new AdaptiveModelRoutingPolicy.ModelProfile("open-fast", "open-analyze", "open-deep"),
                LlmProvider.ANTHROPIC, new AdaptiveModelRoutingPolicy.ModelProfile("anthropic-fast", "anthropic-analyze", "anthropic-deep")));
        RouterBackedIntelligenceEngine engine = new RouterBackedIntelligenceEngine(router, models);
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(new AdaptiveProviderRoutingPolicy(
                        List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC), telemetry, quality)),
                engine,
                (request, responses) -> responses.getFirst().text(),
                (request, responses, finalText) -> { });

        IntelligenceRequest request = request(
                "WORKER-SOFTWARE-ENGINEER", "software.coding", "work-runtime", "bounded", 2);
        IntelligenceResult result = fabric.execute(request);

        assertEquals("fallback provider completed the coding analysis", result.text());
        assertEquals(1, result.providerResults().size());
        assertEquals(LlmProvider.ANTHROPIC, result.providerResults().getFirst().provider());
        assertEquals("anthropic-analyze", result.providerResults().getFirst().response().model());

        assertTrue(failedRequest.get().systemContext().contains("requester=WORKER-SOFTWARE-ENGINEER"));
        assertTrue(successfulRequest.get().systemContext().contains("requester=WORKER-SOFTWARE-ENGINEER"));
        assertEquals("open-analyze", failedRequest.get().model());
        assertEquals("anthropic-analyze", successfulRequest.get().model());

        var traces = router.callTrace().forLogicalRequest(request.requestId());
        assertEquals(2, traces.size());
        assertEquals(LlmProvider.OPENAI, traces.get(0).provider());
        assertEquals("open-analyze", traces.get(0).model());
        assertTrue(!traces.get(0).success());
        assertEquals(LlmProvider.ANTHROPIC, traces.get(1).provider());
        assertEquals("anthropic-analyze", traces.get(1).model());
        assertTrue(traces.get(1).success());
        assertEquals("PROVIDER_FAILURE", traces.get(1).reasonCode());
    }

    private static IntelligenceRequest request(String requester, String capability,
                                               String latencyBudget, String costBudget, int maxProviders) {
        return new IntelligenceRequest(
                "routing-conformance-" + requester + "-" + capability + "-" + latencyBudget + "-" + costBudget,
                requester,
                IntelligenceMode.REASONING,
                CollaborationMode.SINGLE,
                "Perform institutional work",
                "case_id=case-routing-conformance",
                List.of("evidence:request-context"),
                capability,
                "LOW",
                latencyBudget,
                costBudget,
                "authority:test",
                "evidence-backed result",
                List.of(),
                maxProviders,
                false);
    }
}
