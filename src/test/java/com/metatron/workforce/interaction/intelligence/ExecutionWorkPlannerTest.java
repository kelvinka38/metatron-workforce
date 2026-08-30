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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionWorkPlannerTest {
    @Test
    void plannerReceivesCaseAndNormalizedRequestNotRawHumanUtterance() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("case-123"));
                assertTrue(request.userInput().contains("objective=execute governed repository audit"));
                assertTrue(request.userInput().contains("repository.audit.read"));
                assertFalse(request.userInput().contains("Đụ má audit cái repo này giùm tao"));
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[{
                          "step_id":"audit-repo",
                          "objective":"audit repository and preserve evidence",
                          "target":"kelvinka38/bios",
                          "required_capability":"repository.audit.read",
                          "depends_on":[],
                          "consequence":"READ_ONLY"
                        }]}
                        """, "planner-ref");
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), mapper);
        NormalizedRequest normalized = normalizedExecution();

        List<ExecutionWorkSpec> plan = planner.plan("case-123", normalized, List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals("repository.audit.read", plan.getFirst().requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, plan.getFirst().consequence());
    }

    @Test
    void unavailablePlannerProviderCannotCreateExecutionPlan() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> planner.plan("case-123", normalizedExecution(), List.of("repository.audit.read")));

        assertEquals("execution_planning_provider_required", failure.getMessage());
    }

    private static NormalizedRequest normalizedExecution() {
        return new NormalizedRequest(
                "execute governed repository audit", "kelvinka38/bios", List.of("preserve evidence"),
                IntelligenceDepth.ANALYZE, "terminal audit result", List.of(), List.of("do not mutate"),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");
    }
}
