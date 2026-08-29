package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfiguredProviderRoutingPolicyTest {

    @Test
    void preservesConfiguredPriorityWithoutClaimingLiveCapacity() {
        ConfiguredProviderRoutingPolicy policy = new ConfiguredProviderRoutingPolicy(
                List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE, LlmProvider.ANTHROPIC));

        assertEquals(
                List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE),
                policy.select(request(List.of(), 2)));
    }

    @Test
    void explicitProviderMustActuallyBeConfigured() {
        ConfiguredProviderRoutingPolicy policy = new ConfiguredProviderRoutingPolicy(
                List.of(LlmProvider.OPENAI));

        assertEquals(List.of(), policy.select(request(List.of(LlmProvider.ANTHROPIC), 1)));
        assertEquals(List.of(LlmProvider.OPENAI), policy.select(request(List.of(LlmProvider.OPENAI), 1)));
    }

    @Test
    void duplicateConfiguredProviderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredProviderRoutingPolicy(
                List.of(LlmProvider.OPENAI, LlmProvider.OPENAI)));
    }

    private static IntelligenceRequest request(List<LlmProvider> requested, int maxProviders) {
        return new IntelligenceRequest(
                "configured-routing-test",
                "worker-test",
                IntelligenceMode.DISCUSSION,
                CollaborationMode.SINGLE,
                "test objective",
                "test context",
                List.of(),
                "analysis",
                "LOW",
                "interactive",
                "standard",
                "",
                "answer",
                requested,
                maxProviders);
    }
}
