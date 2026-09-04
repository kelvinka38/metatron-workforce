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

final class ExecutionWorkPlannerCrossRepositoryAuditJoinTest {
    @Test
    void materializesRepositoryAuditFanOutBeforeStandaloneCrossRepositoryJoin() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.systemContext().contains("cross-repository-audit-analysis"));
                assertTrue(request.systemContext().contains("For a multi-repository audit, create one `repository.audit.read` step per repository"));
                assertTrue(request.systemContext().contains("Never use `cross-repository-audit-analysis` for a single-repository audit"));
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[{
                          "step_id":"step-1",
                          "objective":"Perform a governed read-only institutional audit of all four repositories in parallel with risk and improvement analysis.",
                          "target":"kelvinka38/universal, kelvinka38/metatron-institution, kelvinka38/metatron-workforce, kelvinka38/bios",
                          "required_capability":"cross-repository-audit-analysis",
                          "depends_on":[],
                          "consequence":"READ_ONLY",
                          "acceptance_criteria":["Audit findings, risk analyses, and improvement recommendations are generated for all four specified repositories without any repository mutations."],
                          "evidence_requirements":["Cross-repository audit report output containing repository analysis records for all four repositories."]
                        }]}
                        """, "planner-ref");
            }
        };

        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-gs1",
                crossRepositoryAuditRequest(),
                List.of("repository.audit.read", "cross-repository-audit-analysis"));

        assertEquals(5, plan.size());
        assertEquals(List.of(
                "kelvinka38/universal",
                "kelvinka38/metatron-institution",
                "kelvinka38/metatron-workforce",
                "kelvinka38/bios"),
                plan.subList(0, 4).stream().map(ExecutionWorkSpec::target).toList());
        assertTrue(plan.subList(0, 4).stream().allMatch(step ->
                step.requiredCapability().equals("repository.audit.read")
                        && step.dependsOn().isEmpty()
                        && step.consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                        && step.verifiable()));

        ExecutionWorkSpec join = plan.get(4);
        assertEquals("step-1", join.stepId());
        assertEquals("cross-repository-audit-analysis", join.requiredCapability());
        assertEquals(List.of(
                "step-1-audit-1",
                "step-1-audit-2",
                "step-1-audit-3",
                "step-1-audit-4"), join.dependsOn());
        assertTrue(join.acceptanceCriteria().getFirst().contains("all four specified repositories"));
        assertTrue(join.verifiable());
    }


    @Test
    void boundedCrossRepositoryAuditFallsBackWhenPlannerProviderFails() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                throw new IllegalStateException("simulated planner provider outage");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-gs1-provider-outage",
                crossRepositoryAuditRequest(),
                List.of("repository.audit.read", "cross-repository-audit-analysis"));

        assertEquals(5, plan.size());
        assertEquals(List.of(
                "kelvinka38/universal",
                "kelvinka38/metatron-institution",
                "kelvinka38/metatron-workforce",
                "kelvinka38/bios"),
                plan.subList(0, 4).stream().map(ExecutionWorkSpec::target).toList());
        assertTrue(plan.subList(0, 4).stream().allMatch(step ->
                step.requiredCapability().equals("repository.audit.read")
                        && step.dependsOn().isEmpty()
                        && step.consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                        && step.verifiable()));

        ExecutionWorkSpec join = plan.getLast();
        assertEquals("cross-repository-audit-analysis", join.requiredCapability());
        assertEquals(List.of(
                "repository-audit-read-1",
                "repository-audit-read-2",
                "repository-audit-read-3",
                "repository-audit-read-4"), join.dependsOn());
        assertTrue(join.verifiable());
    }

    private static NormalizedRequest crossRepositoryAuditRequest() {
        return new NormalizedRequest(
                "Perform a governed read-only institutional audit of kelvinka38/universal, kelvinka38/metatron-institution, kelvinka38/metatron-workforce, and kelvinka38/bios with independent planning, parallel execution, result joining, observation verification, autonomous recovery, and evidence-backed completion delivery without mutations.",
                "kelvinka38/universal, kelvinka38/metatron-institution, kelvinka38/metatron-workforce, kelvinka38/bios",
                List.of(
                        "Read-only institutional audit",
                        "Do not mutate anything",
                        "Independently plan, execute, join, and verify through Observation",
                        "Recover autonomously from routine transient execution failure",
                        "Deliver one evidence-backed completion"),
                IntelligenceDepth.DEEP,
                "direct natural-language answer",
                List.of(),
                List.of("Do not mutate anything"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT, AnalyticalProtocolType.RISK, AnalyticalProtocolType.IMPROVEMENT),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                true,
                null,
                LlmProvider.GOOGLE,
                "");
    }
}
