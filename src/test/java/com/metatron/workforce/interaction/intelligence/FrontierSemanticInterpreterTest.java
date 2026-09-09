package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    void selfContainedFreshQueryFallsBackConservativelyWhenAllSemanticProvidersFail() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                throw new IllegalStateException("google_request_failed:503:temporarily unavailable");
            }
        };
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                throw new IllegalStateException("anthropic_request_failed:429:rate limit");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google, anthropic)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC), new ObjectMapper());

        String human = "Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời.";
        NormalizedRequest normalized = interpreter.interpret(human, "", "telegram");

        assertEquals(human, normalized.objective());
        assertEquals(IntelligenceDepth.ANALYZE, normalized.requestedDepth());
        assertEquals(IntelligenceMode.REASONING, normalized.mode());
        assertEquals(CollaborationMode.SINGLE, normalized.collaborationMode());
        assertEquals(CaseContinuity.NEW, normalized.caseContinuity());
        assertTrue(normalized.freshExternalDataRequired());
        assertNull(normalized.semanticProvider());
        assertTrue(normalized.directResponse().isBlank());
        assertTrue(normalized.analyticalProtocols().isEmpty());
        assertEquals(DeterministicCapability.NONE, normalized.deterministicCapability());
    }

    @Test
    void contextDependentFreshFollowupStillFailsClosedWhenSemanticProvidersAreUnavailable() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                throw new IllegalStateException("google_request_failed:503:temporarily unavailable");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> interpreter.interpret(
                        "Còn hiện tại thì sao?",
                        "Human: Giá Bitcoin trước đó là bao nhiêu?",
                        "telegram"));

        assertTrue(failure.getMessage().contains("all semantic providers failed"));
        assertTrue(failure.getMessage().contains("503"));
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

    @Test
    void explicitFreshRequestIsIsolatedFromStaleActiveCaseSubject() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("CURRENT HUMAN MESSAGE:\nGiá Bitcoin hiện tại"), request.userInput());
                assertTrue(request.userInput().contains(
                        "ACTIVE INTELLIGENCE CASE (runtime coordination only):\nNONE"), request.userInput());
                assertFalse(request.userInput().contains("latest stable Python version"), request.userInput());
                return semanticResponse(LlmProvider.GOOGLE, "provide the current Bitcoin price in USD and VND",
                        "Bitcoin price", "ANALYZE", "REASONING", "", "");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        Instant now = Instant.now();
        IntelligenceCase stalePython = new IntelligenceCase(
                "case-python", "conversation:human:human-primary", "human:human-primary",
                "latest stable Python version", IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.RESULT_READY,
                List.of(), List.of("https://python.org/"), List.of(), List.of(), List.of(), List.of(), List.of(),
                "Python result", "", List.of(), now, now);

        NormalizedRequest normalized = interpreter.interpret(
                "Giá Bitcoin hiện tại khoảng bao nhiêu USD và VND? Hãy dùng dữ liệu mới và nêu nguồn.",
                "", "telegram", stalePython);

        assertEquals("provide the current Bitcoin price in USD and VND", normalized.objective());
        assertEquals(CaseContinuity.NEW, normalized.caseContinuity());
        assertTrue(normalized.freshExternalDataRequired());
    }

    @Test
    void currentWeatherCannotBeMisroutedToCurrentTimeCapability() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return semanticResponseWithCapability(LlmProvider.GOOGLE,
                        "provide current weather in Ho Chi Minh City", "Ho Chi Minh City weather",
                        "ANALYZE", "REASONING", "CURRENT_TIME");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret(
                "Thời tiết hiện tại ở Thành phố Hồ Chí Minh thế nào? Kiểm tra dữ liệu mới và nêu nguồn.",
                "", "telegram");

        assertEquals(DeterministicCapability.NONE, normalized.deterministicCapability());
        assertTrue(normalized.freshExternalDataRequired());
        assertEquals(IntelligenceMode.REASONING, normalized.mode());
    }

    @Test
    void explicitCurrentTimeRequestKeepsCurrentTimeCapability() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return semanticResponseWithCapability(LlmProvider.GOOGLE,
                        "tell the current local time", "local time", "FAST", "DISCUSSION", "CURRENT_TIME");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret("Bây giờ là mấy giờ?", "", "telegram");

        assertEquals(DeterministicCapability.CURRENT_TIME, normalized.deterministicCapability());
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

    private static LlmResponse semanticResponseWithCapability(LlmProvider provider, String objective,
                                                              String target, String depth, String mode,
                                                              String capability) {
        String text = """
                {
                  "objective":"%s",
                  "target":"%s",
                  "constraints":["use evidence"],
                  "requested_depth":"%s",
                  "requested_output":"direct natural-language answer",
                  "explicit_assumptions":[],
                  "explicit_prohibitions":[],
                  "temporal_context":"",
                  "unresolved_semantic_ambiguity":"",
                  "mode":"%s",
                  "collaboration_mode":"SINGLE",
                  "analytical_protocols":[],
                  "deterministic_capability":"%s",
                  "deterministic_computations":[],
                  "fresh_external_data_required":false,
                  "explicitly_requested_provider":null,
                  "direct_response":""
                }
                """.formatted(objective, target, depth, mode, capability);
        return new LlmResponse(provider, "semantic-test", text, "semantic-ref");
    }
}
