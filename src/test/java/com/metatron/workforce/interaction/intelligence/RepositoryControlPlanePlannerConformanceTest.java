package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RC-01 planner acceptance: frontier output cannot turn repository auth into Worker Work. */
final class RepositoryControlPlanePlannerConformanceTest {
    @Test
    void frontierGithubConnectStageIsRejectedBeforeWorkerExecution() {
        LlmProviderClient provider = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[{
                          "step_id":"connect-github",
                          "objective":"Connect GitHub so the coding Worker can continue",
                          "target":"kelvinka38/metatron-workforce",
                          "required_capability":"UNAVAILABLE:github-connect",
                          "depends_on":[],
                          "consequence":"MUTATING",
                          "acceptance_criteria":["GitHub is connected"],
                          "evidence_requirements":["connection evidence"]
                        }]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(provider)), ignored -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest request = new NormalizedRequest(
                "repair code and open a reviewable pull request",
                "kelvinka38/metatron-workforce",
                List.of("use governed coding capability"),
                IntelligenceDepth.ANALYZE,
                "reviewable pull request",
                List.of(), List.of("do not merge", "do not deploy"),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.IMPROVEMENT), DeterministicCapability.NONE,
                List.of(), List.of(), false, LlmProvider.GOOGLE, LlmProvider.GOOGLE, "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> planner.plan("case-repository-control-plane", request,
                        List.of("execution.general.workspace")));

        assertTrue(failure.getMessage().contains("all execution planning providers failed"));
        assertTrue(List.of(failure.getSuppressed()).stream()
                .anyMatch(value -> value.getMessage().contains("repository-control-plane-dependency-cannot-be-work-step")));
    }
}
