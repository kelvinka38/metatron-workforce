package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrontierSemanticDelegatedExecutionContractTest {

    @Test
    void delegatedReadOnlyInstitutionalAuditIsExecutionWithoutKeywordRouting() {
        LlmProviderClient provider = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.systemContext().contains("delegated read-only institutional audit is EXECUTION"));
                assertTrue(request.systemContext().contains("Do not downgrade such work to REASONING"));
                assertTrue(request.systemContext().contains("without accepting durable institutional ownership"));
                assertTrue(request.userInput().contains("kelvinka38/universal"));
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"perform governed read-only institutional audit of four repositories",
                          "target":"Metatron canonical repositories",
                          "constraints":["read-only","obtain evidence independently","deliver one evidence-backed completion"],
                          "requested_depth":"DEEP",
                          "requested_output":"evidence-backed completion",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":["mutation"],
                          "temporal_context":"",
                          "unresolved_semantic_ambiguity":"",
                          "mode":"EXECUTION",
                          "collaboration_mode":"SINGLE",
                          "analytical_protocols":["AUDIT"],
                          "deterministic_capability":"NONE",
                          "deterministic_computations":[],
                          "fresh_external_data_required":true,
                          "explicitly_requested_provider":null,
                          "case_continuity":"NEW",
                          "direct_response":""
                        }
                        """, "semantic-ref");
            }
        };

        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(provider)), ignored -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret(
                "Take ownership of one Objective: audit kelvinka38/universal, kelvinka38/metatron-institution, " +
                        "kelvinka38/metatron-workforce and kelvinka38/bios. Do not mutate anything.",
                "", "telegram");

        assertEquals(IntelligenceMode.EXECUTION, normalized.mode());
        assertEquals(IntelligenceDepth.DEEP, normalized.requestedDepth());
        assertTrue(normalized.analyticalProtocols().contains(AnalyticalProtocolType.AUDIT));
    }
}
