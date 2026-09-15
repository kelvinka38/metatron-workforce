package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.management.CognitionRuntimeAssuranceCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionWorkPlannerCognitionRuntimeAssuranceTest {
    @Test
    void workerCognitionAssuranceIsOneDeterministicReadOnlyStep() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()),
                provider -> "unused",
                List.of(),
                new ObjectMapper());

        NormalizedRequest request = new NormalizedRequest(
                "Verify and, if necessary, reconcile Worker cognition to use llama3.1:8b-instruct-q4_K_M through the Metatron-owned cognition path without requiring external provider credits",
                "Metatron Worker cognition runtime",
                List.of("Do not require external provider credits"),
                IntelligenceDepth.ANALYZE,
                "Prove the resulting Worker assignment, governed capability path, and final evidence",
                List.of(),
                List.of("No external provider dependency"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                false,
                null,
                LlmProvider.OPENAI,
                "");

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:cognition-assurance",
                request,
                List.of(CognitionRuntimeAssuranceCapability.CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec step = plan.getFirst();
        assertEquals("cognition-runtime-assurance", step.stepId());
        assertEquals(CognitionRuntimeAssuranceCapability.CAPABILITY, step.requiredCapability());
        assertEquals("llama3.1:8b-instruct-q4_K_M", step.target());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, step.consequence());
        assertTrue(step.acceptanceCriteria().stream().anyMatch(value -> value.contains("Metatron-owned")));
        assertTrue(step.evidenceRequirements().contains("metatron-cognition-model:llama3.1:8b-instruct-q4_K_M"));
    }
}
