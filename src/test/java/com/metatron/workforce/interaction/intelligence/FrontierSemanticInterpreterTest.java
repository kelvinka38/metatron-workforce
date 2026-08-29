package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class FrontierSemanticInterpreterTest {

    @Test
    void frontierModelNormalizesSlangWithoutAKeywordRouter() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("tụt vì cái gì"));
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"explain current-month Shopee performance decline",
                          "target":"Shopee performance",
                          "constraints":["use evidence","do not guess"],
                          "requested_depth":"ANALYZE",
                          "requested_output":"root-cause analysis",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":["unsupported guessing"],
                          "temporal_context":"current month",
                          "unresolved_semantic_ambiguity":"",
                          "mode":"REASONING",
                          "collaboration_mode":"SINGLE",
                          "deterministic_capability":"NONE",
                          "fresh_external_data_required":false,
                          "explicitly_requested_provider":null,
                          "direct_response":""
                        }
                        """, "semantic-ref");
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
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"greet the human",
                          "target":"",
                          "constraints":[],
                          "requested_depth":"FAST",
                          "requested_output":"direct natural-language answer",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":[],
                          "temporal_context":"",
                          "unresolved_semantic_ambiguity":"",
                          "mode":"DISCUSSION",
                          "collaboration_mode":"SINGLE",
                          "deterministic_capability":"NONE",
                          "fresh_external_data_required":false,
                          "explicitly_requested_provider":null,
                          "direct_response":"Chào mày, cần tao xử gì?"
                        }
                        """, "semantic-ref");
            }
        };

        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret("hello", "", "telegram");
        assertTrue(normalized.canReturnFastDirectly());
        assertEquals("Chào mày, cần tao xử gì?", normalized.directResponse());
    }
}
