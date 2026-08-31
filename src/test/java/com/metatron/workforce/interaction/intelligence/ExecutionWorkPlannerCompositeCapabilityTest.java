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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionWorkPlannerCompositeCapabilityTest {
    @Test
    void reconcilesFalseRepositoryWriteGapIntoBoundedPullRequestCapability() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.systemContext().contains("repository.pr.propose"));
                assertTrue(request.systemContext().contains("do NOT invent a separate `repository.content.write` step"));
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {
                            "step_id":"step-1",
                            "objective":"audit current gap matrix",
                            "target":"kelvinka38/metatron-workforce",
                            "required_capability":"repository.audit.read",
                            "depends_on":[],
                            "consequence":"READ_ONLY",
                            "acceptance_criteria":["current gap matrix inspected"],
                            "evidence_requirements":["repository audit evidence"]
                          },
                          {
                            "step_id":"step-2",
                            "objective":"repair stale gap matrix",
                            "target":"kelvinka38/metatron-workforce",
                            "required_capability":"UNAVAILABLE:repository.content.write",
                            "depends_on":["step-1"],
                            "consequence":"MUTATING",
                            "acceptance_criteria":["gap matrix repaired"],
                            "evidence_requirements":["controlled file diff"]
                          },
                          {
                            "step_id":"step-3",
                            "objective":"verify repaired gap matrix",
                            "target":"kelvinka38/metatron-workforce",
                            "required_capability":"repository.audit.read",
                            "depends_on":["step-2"],
                            "consequence":"READ_ONLY",
                            "acceptance_criteria":["repair independently verified"],
                            "evidence_requirements":["verification report"]
                          },
                          {
                            "step_id":"step-4",
                            "objective":"open pull request without merging",
                            "target":"kelvinka38/metatron-workforce",
                            "required_capability":"repository.pr.propose",
                            "depends_on":["step-3"],
                            "consequence":"MUTATING",
                            "acceptance_criteria":["open unmerged pull request exists"],
                            "evidence_requirements":["pull request URL and unmerged state"]
                          }
                        ]}
                        """, "planner-ref");
            }
        };

        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-gs2",
                mutationRequest(),
                List.of("repository.audit.read", "repository.pr.propose"));

        assertEquals(2, plan.size());
        assertEquals("repository.audit.read", plan.get(0).requiredCapability());
        ExecutionWorkSpec mutation = plan.get(1);
        assertEquals("step-2", mutation.stepId());
        assertEquals("repository.pr.propose", mutation.requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, mutation.consequence());
        assertEquals(List.of("step-1"), mutation.dependsOn());
        assertTrue(mutation.acceptanceCriteria().contains("gap matrix repaired"));
        assertTrue(mutation.acceptanceCriteria().contains("repair independently verified"));
        assertTrue(mutation.acceptanceCriteria().contains("open unmerged pull request exists"));
        assertTrue(mutation.evidenceRequirements().contains("pull request URL and unmerged state"));
        assertFalse(plan.stream().anyMatch(step -> step.requiredCapability().startsWith("UNAVAILABLE:")));
    }

    private static NormalizedRequest mutationRequest() {
        return new NormalizedRequest(
                "repair the stale Workforce Autonomy Closure baseline and open a pull request for Founder approval without merging",
                "kelvinka38/metatron-workforce",
                List.of("Do not merge the pull request"),
                IntelligenceDepth.DEEP,
                "evidence-backed completion",
                List.of(),
                List.of("Do not merge the pull request"),
                "current",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.RISK, AnalyticalProtocolType.IMPROVEMENT),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                true,
                null,
                LlmProvider.GOOGLE,
                "");
    }
}
