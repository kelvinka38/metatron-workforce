package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntelligencePlannerTest {

    @Test
    void casualModeDoesNotRequireGovernance() {
        IntelligenceRequest request = request(IntelligenceMode.CASUAL, CollaborationMode.SINGLE, 1);
        IntelligencePlan plan = planner(List.of(LlmProvider.OPENAI)).plan(request);

        assertFalse(plan.requiresReasoning());
        assertEquals(List.of(LlmProvider.OPENAI), plan.providers());
    }

    @Test
    void reasoningModeRequiresGovernance() {
        IntelligenceRequest request = request(IntelligenceMode.REASONING, CollaborationMode.SINGLE, 1);
        IntelligencePlan plan = planner(List.of(LlmProvider.ANTHROPIC)).plan(request);

        assertTrue(plan.requiresReasoning());
    }

    @Test
    void consensusRequiresAtLeastTwoProviders() {
        IntelligenceRequest request = request(IntelligenceMode.DECISION, CollaborationMode.CONSENSUS, 3);

        IntelligencePlan plan = planner(List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.GOOGLE)).plan(request);

        assertEquals(CollaborationMode.CONSENSUS, plan.collaborationMode());
        assertEquals(3, plan.providers().size());
    }

    @Test
    void providerBudgetIsEnforced() {
        IntelligenceRequest request = request(IntelligenceMode.REASONING, CollaborationMode.CONSENSUS, 2);

        assertThrows(IllegalStateException.class,
                () -> planner(List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.GOOGLE)).plan(request));
    }

    @Test
    void noCapacityIsExplicitlyRejected() {
        IntelligenceRequest request = request(IntelligenceMode.REASONING, CollaborationMode.SINGLE, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> planner(List.of()).plan(request));
        assertTrue(error.getMessage().contains("no intelligence capacity available"));
    }

    @Test
    void singleModeRejectsMultipleProviders() {
        IntelligenceRequest request = request(IntelligenceMode.REASONING, CollaborationMode.SINGLE, 2);
        assertThrows(IllegalArgumentException.class, () -> planner(List.of(LlmProvider.OPENAI)).plan(request));
    }

    private static IntelligencePlanner planner(List<LlmProvider> providers) {
        return new IntelligencePlanner(ignored -> providers);
    }

    private static IntelligenceRequest request(IntelligenceMode mode, CollaborationMode collaborationMode, int maxProviders) {
        return new IntelligenceRequest(
                "test-request",
                mode,
                collaborationMode,
                "test task",
                "test context",
                List.of(),
                maxProviders);
    }
}
