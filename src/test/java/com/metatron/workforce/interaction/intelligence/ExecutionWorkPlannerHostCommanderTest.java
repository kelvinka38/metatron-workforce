package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.management.HostCommanderAutonomousCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionWorkPlannerHostCommanderTest {
    @Test
    void exactFailedProductionAcceptanceRoutesToCommanderNotResearch() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());
        String objective = """
                Perform a real production Host Commander acceptance task. Prove Metatron Workforce to MCP Host Commander to real host works end-to-end.
                Open a governed Founder Host Commander maintenance session. Report current Commander runtime identity and generation.
                Create /tmp/metatron-commander/telegram-live-test.txt with telegram-alpha, read it, patch telegram-alpha to telegram-beta,
                run uptime, inspect Docker container deploy-workforce-1, remove the file, prove secret-path access is denied,
                close the Commander session and prove a stale/closed session is denied. Final report must show evidence.
                """;
        NormalizedRequest request = new NormalizedRequest(
                objective, "real Metatron production host", List.of("no manual SSH", "fail closed"),
                IntelligenceDepth.ANALYZE, "PASS/FAIL report with current runtime evidence", List.of(),
                List.of("do not use external research"), "", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE, List.of(), List.of(),
                true, null, LlmProvider.OPENAI, "");

        List<ExecutionWorkSpec> plan = planner.propose("case:host-commander-live", request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY, HostCommanderAutonomousCapability.CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec step = plan.getFirst();
        assertEquals("host-commander-execution", step.stepId());
        assertEquals(HostCommanderAutonomousCapability.CAPABILITY, step.requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, step.consequence());
        assertNotEquals("general-external-research", step.stepId());
        assertTrue(step.evidenceRequirements().stream().anyMatch(v -> v.contains("host-commander")));
    }

    @Test
    void hostOperationNeverFallsBackToExternalResearchWhenCommanderUnavailable() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());
        NormalizedRequest request = new NormalizedRequest(
                "Run Host Commander on the real host, execute uptime and report current evidence",
                "Metatron host", List.of(), IntelligenceDepth.ANALYZE, "report current evidence", List.of(), List.of(),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE,
                List.of(), List.of(), true, null, LlmProvider.OPENAI, "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> planner.propose("case:host-no-commander", request,
                        List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY)));
        assertEquals("execution_planning_provider_required", failure.getMessage());
    }
}
