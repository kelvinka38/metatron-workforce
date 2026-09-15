package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionWorkPlannerExternalResearchTest {
    @Test
    void externalResearchIsDeterministicallyBoundToGeneralWorkerWithoutPlannerProvider() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()),
                provider -> "unused",
                List.of(),
                new ObjectMapper());

        NormalizedRequest request = new NormalizedRequest(
                "Find exactly 5 useful recent papers, regulator reports, or standards analyses for Mother & Baby vetting",
                "Vietnam-first legal verification, evidence quality, KOL/KOC trust, risk scoring",
                List.of("Top 5 only", "Prefer primary or peer-reviewed sources"),
                IntelligenceDepth.ANALYZE,
                "For each item explain what is new, why it matters, and KEEP, TEST, CHANGE, or REJECT",
                List.of(),
                List.of("No fabricated citations", "No external mutation"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                true,
                null,
                LlmProvider.OPENAI,
                "");

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:mother-baby-research",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec step = plan.getFirst();
        assertEquals("general-external-research", step.stepId());
        assertEquals(GeneralWorkspaceAutonomousCapability.CAPABILITY, step.requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, step.consequence());
        assertTrue(step.acceptanceCriteria().stream().anyMatch(value -> value.contains("exactly 5")));
        assertTrue(step.evidenceRequirements().contains("research-action:research.web.search"));
        assertTrue(step.evidenceRequirements().stream().anyMatch(value -> value.contains("source URLs")));
    }

    @Test
    void internalCognitionObjectiveIsNotHijackedByExternalProviderProhibitionAndEvidenceLanguage() {
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

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> planner.propose(
                "case:internal-cognition-runtime",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY)));

        assertEquals("execution_planning_provider_required", failure.getMessage());
    }
}
