package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class FrontierSemanticInterpreterTest {

    @Test
    void frontierModelNormalizesSlangWithoutAKeywordRouter() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("tụt vì cái gì"));
                return semanticResponse(LlmProvider.GOOGLE, "explain current-month Shopee performance decline",
                        "Shopee performance", "ANALYZE", "REASONING", "", "");
            }
        };

        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret(
                "Đụ mẹ coi dùm tao tháng này Shopee tụt vì cái gì, đừng có đoán nha.", "", "telegram");

        assertEquals("explain current-month Shopee performance decline", normalized.objective());
        assertEquals(IntelligenceDepth.ANALYZE, normalized.requestedDepth());
        assertEquals(IntelligenceMode.REASONING, normalized.mode());
        assertTrue(normalized.constraints().contains("use evidence"));
        assertTrue(normalized.explicitProhibitions().contains("unsupported guessing"));
        assertFalse(normalized.canReturnFastDirectly());
    }

    @Test
    void semanticBoundaryUsesLiveTelemetryToAvoidKnownDegradedProvider() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                googleCalls.incrementAndGet();
                throw new IllegalStateException("should not call degraded provider first");
            }
        };
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                anthropicCalls.incrementAndGet();
                return semanticResponse(LlmProvider.ANTHROPIC, "greet the human", "", "FAST", "DISCUSSION",
                        "Chào mày.", "");
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(google, anthropic));
        long started = router.telemetry().begin(LlmProvider.GOOGLE);
        router.telemetry().failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));

        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                router, provider -> "semantic-test", List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC),
                new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret("hello", "", "web");

        assertEquals(LlmProvider.ANTHROPIC, normalized.semanticProvider());
        assertEquals(0, googleCalls.get());
        assertEquals(1, anthropicCalls.get());
    }

    @Test
    void semanticInterpretationCannotRunWithoutAFrontierProvider() {
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> interpreter.interpret("xin chào", "", "web"));

        assertEquals("semantic_provider_required", failure.getMessage());
    }

    @Test
    void fastConversationCanReuseTheSemanticCallAsItsDirectResponse() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return semanticResponse(LlmProvider.GOOGLE, "greet the human", "", "FAST", "DISCUSSION",
                        "Chào mày, cần tao xử gì?", "");
            }
        };

        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret("hello", "", "telegram");
        assertTrue(normalized.canReturnFastDirectly());
        assertEquals("Chào mày, cần tao xử gì?", normalized.directResponse());
    }

    private static LlmResponse semanticResponse(LlmProvider provider, String objective, String target,
                                                String depth, String mode, String directResponse,
                                                String ambiguity) {
        String text = """
                {
                  "objective":"%s",
                  "target":"%s",
                  "constraints":["use evidence","do not guess"],
                  "requested_depth":"%s",
                  "requested_output":"direct natural-language answer",
                  "explicit_assumptions":[],
                  "explicit_prohibitions":["unsupported guessing"],
                  "temporal_context":"",
                  "unresolved_semantic_ambiguity":"%s",
                  "mode":"%s",
                  "collaboration_mode":"SINGLE",
                  "analytical_protocols":[],
                  "deterministic_capability":"NONE",
                  "deterministic_computations":[],
                  "fresh_external_data_required":false,
                  "explicitly_requested_provider":null,
                  "direct_response":"%s"
                }
                """.formatted(objective, target, depth, ambiguity, mode, directResponse);
        return new LlmResponse(provider, "semantic-test", text, "semantic-ref");
    }
}
