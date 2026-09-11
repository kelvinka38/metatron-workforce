package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveRuntimeFabricTest {
    @Test
    void reusableSingleProviderArtifactAvoidsASecondFrontierCall() {
        AtomicInteger calls = new AtomicInteger();
        IntelligenceEngine engine = (provider, request) -> {
            calls.incrementAndGet();
            return new LlmResponse(provider, "test-model", "reusable answer", "provider-ref");
        };
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE)),
                engine,
                (request, responses) -> "unused",
                (request, responses, finalText) -> {},
                new DefaultToolFabric(List.of()),
                null,
                new InMemoryCognitiveArtifactStore());

        IntelligenceResult first = fabric.execute(request("request-1", CollaborationMode.SINGLE, 1));
        IntelligenceResult second = fabric.execute(request("request-2", CollaborationMode.SINGLE, 1));

        assertEquals("reusable answer", first.text());
        assertEquals("reusable answer", second.text());
        assertEquals(1, calls.get(), "second equivalent cognition must reuse the Metatron-owned artifact");
        assertTrue(second.providerResults().isEmpty(), "artifact reuse must not pretend a provider was called again");
    }

    @Test
    void singleProviderFailoverCarriesProviderFailureReason() {
        List<EscalationReason> reasons = new ArrayList<>();
        IntelligenceEngine engine = new IntelligenceEngine() {
            @Override
            public LlmResponse execute(LlmProvider provider, IntelligenceRequest request) {
                return execute(provider, request, null);
            }

            @Override
            public LlmResponse execute(LlmProvider provider, IntelligenceRequest request, EscalationReason reason) {
                reasons.add(reason);
                if (provider == LlmProvider.GOOGLE) throw new IllegalStateException("quota");
                return new LlmResponse(provider, "test-model", "fallback answer", "provider-ref");
            }
        };
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI)),
                engine,
                (request, responses) -> "unused",
                (request, responses, finalText) -> {});

        IntelligenceResult result = fabric.execute(request("request-fallback", CollaborationMode.SINGLE, 2));

        assertEquals("fallback answer", result.text());
        assertEquals(2, reasons.size());
        assertNull(reasons.get(0));
        assertEquals(EscalationReason.PROVIDER_FAILURE, reasons.get(1));
    }

    @Test
    void explicitlyCollaborativeSecondProviderIsReasonCoded() {
        List<EscalationReason> reasons = new ArrayList<>();
        IntelligenceEngine engine = new IntelligenceEngine() {
            @Override
            public LlmResponse execute(LlmProvider provider, IntelligenceRequest request) {
                return execute(provider, request, null);
            }

            @Override
            public LlmResponse execute(LlmProvider provider, IntelligenceRequest request, EscalationReason reason) {
                reasons.add(reason);
                return new LlmResponse(provider, "test-model", provider.name() + " answer", "provider-ref");
            }
        };
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI)),
                engine,
                (request, responses) -> "synthesized",
                (request, responses, finalText) -> {});

        IntelligenceResult result = fabric.execute(request(
                "request-collaboration", CollaborationMode.INDEPENDENT_SECOND_OPINION, 2));

        assertEquals("synthesized", result.text());
        assertEquals(2, reasons.size());
        assertNull(reasons.get(0));
        assertEquals(EscalationReason.EXPLICIT_HUMAN_REQUEST, reasons.get(1));
    }

    private static IntelligenceRequest request(String id, CollaborationMode collaboration, int maxProviders) {
        return new IntelligenceRequest(
                id,
                "human:test",
                IntelligenceMode.REASONING,
                collaboration,
                "same objective",
                "stable context",
                List.of("evidence:stable"),
                "analysis",
                "MEDIUM",
                "standard",
                "standard",
                "",
                "answer",
                List.of(),
                maxProviders,
                false);
    }
}
