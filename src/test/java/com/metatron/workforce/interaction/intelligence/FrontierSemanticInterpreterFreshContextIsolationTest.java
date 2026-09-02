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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrontierSemanticInterpreterFreshContextIsolationTest {

    @Test
    void selfContainedFreshRequestHidesStaleCaseAndHistory() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("CURRENT HUMAN MESSAGE:\nGiá Bitcoin hiện tại"), request.userInput());
                assertTrue(request.userInput().contains(
                        "ACTIVE INTELLIGENCE CASE (runtime coordination only):\nNONE"), request.userInput());
                assertFalse(request.userInput().contains("current weather in Ho Chi Minh City"), request.userInput());
                assertFalse(request.userInput().contains("Thời tiết hiện tại ở Thành phố Hồ Chí Minh"), request.userInput());
                return semanticResponse("provide the current Bitcoin price in USD and VND", "Bitcoin price");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        Instant now = Instant.now();
        IntelligenceCase staleWeather = new IntelligenceCase(
                "case-weather", "conversation:human:human-primary", "human:human-primary",
                "current weather in Ho Chi Minh City", IntelligenceDepth.ANALYZE,
                IntelligenceCaseStatus.RESULT_READY, List.of(), List.of("https://open-meteo.com/"),
                List.of(), List.of(), List.of(), List.of(), List.of(), "weather result", "", List.of(), now, now);

        NormalizedRequest normalized = interpreter.interpret(
                "Giá Bitcoin hiện tại khoảng bao nhiêu USD và VND? Hãy dùng dữ liệu mới và nêu nguồn.",
                "Human: Thời tiết hiện tại ở Thành phố Hồ Chí Minh thế nào?\nAssistant: weather result",
                "telegram", staleWeather);

        assertEquals("provide the current Bitcoin price in USD and VND", normalized.objective());
        assertEquals(CaseContinuity.NEW, normalized.caseContinuity());
        assertTrue(normalized.freshExternalDataRequired());
    }

    @Test
    void contextDependentFreshFollowupRetainsConversationContext() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("CURRENT HUMAN MESSAGE:\nCòn hiện tại thì sao?"), request.userInput());
                assertTrue(request.userInput().contains("ACTIVE INTELLIGENCE CASE (runtime coordination only):\ncase_id=case-bitcoin"), request.userInput());
                assertTrue(request.userInput().contains("Human: Giá Bitcoin trước đó là bao nhiêu?"), request.userInput());
                return semanticResponse("refresh the current Bitcoin price", "Bitcoin price");
            }
        };
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        Instant now = Instant.now();
        IntelligenceCase active = new IntelligenceCase(
                "case-bitcoin", "conversation:human:human-primary", "human:human-primary",
                "Bitcoin price", IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.RESULT_READY,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "old Bitcoin result", "", List.of(), now, now);

        NormalizedRequest normalized = interpreter.interpret(
                "Còn hiện tại thì sao?", "Human: Giá Bitcoin trước đó là bao nhiêu?", "telegram", active);

        assertEquals("refresh the current Bitcoin price", normalized.objective());
        assertEquals(CaseContinuity.CONTINUE, normalized.caseContinuity());
        assertTrue(normalized.freshExternalDataRequired());
    }

    private static LlmResponse semanticResponse(String objective, String target) {
        String text = """
                {
                  "objective":"%s",
                  "target":"%s",
                  "constraints":["use evidence"],
                  "requested_depth":"ANALYZE",
                  "requested_output":"direct natural-language answer",
                  "explicit_assumptions":[],
                  "explicit_prohibitions":[],
                  "temporal_context":"current",
                  "unresolved_semantic_ambiguity":"",
                  "interaction_outcome":"ANSWER",
                  "evidence_scope":"CURRENT_EXTERNAL",
                  "mode":"REASONING",
                  "collaboration_mode":"SINGLE",
                  "analytical_protocols":[],
                  "deterministic_capability":"NONE",
                  "deterministic_computations":[],
                  "fresh_external_data_required":true,
                  "explicitly_requested_provider":null,
                  "direct_response":""
                }
                """.formatted(objective, target);
        return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", text, "semantic-ref");
    }
}
