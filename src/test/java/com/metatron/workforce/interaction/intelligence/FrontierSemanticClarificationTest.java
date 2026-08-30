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

final class FrontierSemanticClarificationTest {
    @Test
    void materialHumanOnlyAmbiguityBecomesOneDirectClarificationInsteadOfGuessedReasoning() {
        LlmProviderClient provider = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.systemContext().contains("Do not guess through material"));
                assertTrue(request.systemContext().contains("case_continuity"));
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"clarify which project the Human means",
                          "target":"",
                          "constraints":[],
                          "requested_depth":"FAST",
                          "requested_output":"direct natural-language answer",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":[],
                          "temporal_context":"",
                          "unresolved_semantic_ambiguity":"Mày đang nói Workforce hay Gateway?",
                          "mode":"DISCUSSION",
                          "collaboration_mode":"SINGLE",
                          "analytical_protocols":[],
                          "deterministic_capability":"NONE",
                          "deterministic_computations":[],
                          "fresh_external_data_required":false,
                          "explicitly_requested_provider":null,
                          "case_continuity":"NEW",
                          "direct_response":"Mày đang nói Workforce hay Gateway?"
                        }
                        """, "semantic-ref");
            }
        };

        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(provider)), ignored -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret("Audit cái kia đi", "two projects are in context", "telegram");

        assertTrue(normalized.materiallyAmbiguous());
        assertTrue(normalized.canReturnFastDirectly());
        assertEquals("Mày đang nói Workforce hay Gateway?", normalized.directResponse());
        assertEquals(IntelligenceMode.DISCUSSION, normalized.mode());
        assertEquals(CaseContinuity.NEW, normalized.caseContinuity());
        assertTrue(normalized.analyticalProtocols().isEmpty());
    }
}
