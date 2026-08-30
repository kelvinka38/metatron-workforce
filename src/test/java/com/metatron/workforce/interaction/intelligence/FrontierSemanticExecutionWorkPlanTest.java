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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrontierSemanticExecutionWorkPlanTest {
    private static final String REQUEST =
            "Execute a governed read-only repository audit of kelvinka38/bios and return evidence. Do not mutate anything.";

    @Test
    void knownRepositoryAuditStillRequiresFrontierSemanticNormalizationBeforeCapabilityBinding() {
        AtomicInteger semanticCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                semanticCalls.incrementAndGet();
                assertTrue(request.userInput().contains("repository.audit.read"));
                assertTrue(request.userInput().contains(REQUEST));
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"execute a governed read-only repository audit and return evidence",
                          "target":"kelvinka38/bios",
                          "constraints":["preserve evidence"],
                          "requested_depth":"ANALYZE",
                          "requested_output":"terminal audit result with evidence",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":["do not mutate repository"],
                          "temporal_context":"current",
                          "unresolved_semantic_ambiguity":"",
                          "mode":"EXECUTION",
                          "collaboration_mode":"SINGLE",
                          "analytical_protocols":["AUDIT"],
                          "deterministic_capability":"NONE",
                          "deterministic_computations":[],
                          "execution_work_plan":[
                            {
                              "step_id":"audit-bios",
                              "objective":"audit the BIOS repository and preserve evidence",
                              "target":"kelvinka38/bios",
                              "required_capability":"repository.audit.read",
                              "depends_on":[],
                              "consequence":"READ_ONLY"
                            }
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

        NormalizedRequest request = interpreter.interpret(
                REQUEST, "", "telegram", List.of("repository.audit.read"));

        assertEquals(1, semanticCalls.get());
        assertEquals(LlmProvider.GOOGLE, request.semanticProvider());
        assertEquals(IntelligenceMode.EXECUTION, request.mode());
        assertEquals(1, request.executionWorkPlan().size());
        ExecutionWorkSpec work = request.executionWorkPlan().getFirst();
        assertEquals("repository.audit.read", work.requiredCapability());
        assertEquals("kelvinka38/bios", work.target());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, work.consequence());
    }

    @Test
    void providerUnavailabilityCannotFallBackToRawTextExecutionRecognition() {
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> interpreter.interpret(REQUEST, "", "telegram", List.of("repository.audit.read")));

        assertEquals("semantic_provider_required", failure.getMessage());
    }
}
