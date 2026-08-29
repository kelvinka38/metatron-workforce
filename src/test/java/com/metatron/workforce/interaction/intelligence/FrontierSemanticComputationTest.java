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

final class FrontierSemanticComputationTest {
    @Test
    void semanticLayerDeclaresArithmeticButDoesNotCalculateIt() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.systemContext().contains("deterministic_computations"));
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"calculate growth from 100 to 120",
                          "target":"growth rate",
                          "constraints":[],
                          "requested_depth":"FAST",
                          "requested_output":"direct natural-language answer",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":[],
                          "temporal_context":"",
                          "unresolved_semantic_ambiguity":"",
                          "mode":"DISCUSSION",
                          "collaboration_mode":"SINGLE",
                          "analytical_protocols":[],
                          "deterministic_capability":"NONE",
                          "deterministic_computations":[
                            {"label":"growth","operation":"PERCENT_CHANGE","operands":["120","100"],"unit":"%"}
                          ],
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

        NormalizedRequest normalized = interpreter.interpret("Từ 100 lên 120 là tăng bao nhiêu %?", "", "telegram");

        assertEquals(1, normalized.deterministicComputations().size());
        DeterministicComputationSpec spec = normalized.deterministicComputations().getFirst();
        assertEquals(DeterministicComputationOperation.PERCENT_CHANGE, spec.operation());
        assertEquals(List.of("120", "100"), spec.operands());
        assertFalse(normalized.canReturnFastDirectly());
    }
}
