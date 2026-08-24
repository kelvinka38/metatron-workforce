package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapacityAwareRoutingPolicyTest {

    @Test
    void automaticSingleSelectionUsesAvailableCapacityRanking() {
        CapacityAwareRoutingPolicy policy = new CapacityAwareRoutingPolicy(List.of(
                capacity(LlmProvider.OPENAI, true, 10, 1, 1000, 200, 100),
                capacity(LlmProvider.ANTHROPIC, true, 20, 1, 1000, 300, 200),
                capacity(LlmProvider.GOOGLE, false, 100, 1, 1000, 50, 50)));

        IntelligenceRequest request = request(CollaborationMode.SINGLE, 1, List.of());

        assertEquals(List.of(LlmProvider.ANTHROPIC), policy.select(request));
    }

    @Test
    void unavailableRequestedProviderIsNotSelected() {
        CapacityAwareRoutingPolicy policy = new CapacityAwareRoutingPolicy(List.of(
                capacity(LlmProvider.OPENAI, false, 100, 1, 1000, 100, 100),
                capacity(LlmProvider.ANTHROPIC, true, 10, 1, 1000, 100, 100)));

        IntelligenceRequest request = request(CollaborationMode.SINGLE, 1, List.of(LlmProvider.OPENAI));

        assertTrue(policy.select(request).isEmpty());
    }

    @Test
    void consensusSelectsOnlyAvailableProvidersUpToBudget() {
        CapacityAwareRoutingPolicy policy = new CapacityAwareRoutingPolicy(List.of(
                capacity(LlmProvider.OPENAI, true, 30, 1, 1000, 100, 100),
                capacity(LlmProvider.ANTHROPIC, true, 20, 1, 1000, 100, 100),
                capacity(LlmProvider.GOOGLE, true, 10, 1, 1000, 100, 100)));

        IntelligenceRequest request = request(CollaborationMode.CONSENSUS, 2, List.of());

        assertEquals(List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC), policy.select(request));
    }

    @Test
    void zeroConcurrencyIsNotUsable() {
        CapacityAwareRoutingPolicy policy = new CapacityAwareRoutingPolicy(List.of(
                capacity(LlmProvider.OPENAI, true, 100, 0, 1000, 100, 100)));

        IntelligenceRequest request = request(CollaborationMode.SINGLE, 1, List.of());

        assertTrue(policy.select(request).isEmpty());
    }

    private static ProviderCapacity capacity(LlmProvider provider, boolean available, int priority,
                                             int concurrency, long tokens, long latency, long cost) {
        return new ProviderCapacity(provider, available, priority, concurrency, tokens, latency, cost);
    }

    private static IntelligenceRequest request(CollaborationMode mode, int maxProviders, List<LlmProvider> providers) {
        return new IntelligenceRequest(
                "capacity-test",
                "worker-test",
                IntelligenceMode.REASONING,
                mode,
                "select provider",
                "context",
                List.of("evidence:test"),
                "analysis",
                "medium",
                "10s",
                "budget",
                "authority",
                "result",
                providers,
                maxProviders);
    }
}
