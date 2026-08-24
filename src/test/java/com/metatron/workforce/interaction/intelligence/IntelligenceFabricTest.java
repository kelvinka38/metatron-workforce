package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntelligenceFabricTest {

    @Test
    void singleProviderReturnsAttributedResponseAndRunsGovernanceForReasoning() {
        List<String> governanceCalls = new ArrayList<>();
        IntelligenceFabric fabric = fabric(
                List.of(LlmProvider.OPENAI),
                (request, responses, finalText) -> governanceCalls.add(finalText));

        IntelligenceResult result = fabric.execute(request(IntelligenceMode.REASONING, CollaborationMode.SINGLE, 1));

        assertEquals("OPENAI response", result.text());
        assertEquals(List.of(LlmProvider.OPENAI), result.providerResults().stream().map(IntelligenceResult.ProviderResult::provider).toList());
        assertEquals(List.of("OPENAI response"), governanceCalls);
    }

    @Test
    void casualDoesNotInvokeGovernance() {
        List<String> governanceCalls = new ArrayList<>();
        IntelligenceFabric fabric = fabric(
                List.of(LlmProvider.OPENAI),
                (request, responses, finalText) -> governanceCalls.add(finalText));

        IntelligenceResult result = fabric.execute(request(IntelligenceMode.CASUAL, CollaborationMode.SINGLE, 1));

        assertEquals("OPENAI response", result.text());
        assertTrue(governanceCalls.isEmpty());
    }

    @Test
    void multiProviderProducesOneSynthesizedResult() {
        List<String> governanceCalls = new ArrayList<>();
        IntelligenceFabric fabric = new IntelligenceFabric(
                planner(List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC)),
                (provider, request) -> response(provider, provider.name() + " proposal"),
                (request, responses) -> responses.stream().map(LlmResponse::text).reduce((a, b) -> a + " + " + b).orElseThrow(),
                (request, responses, finalText) -> governanceCalls.add(finalText));

        IntelligenceResult result = fabric.execute(request(IntelligenceMode.DECISION, CollaborationMode.CONSENSUS, 2));

        assertEquals("OPENAI proposal + ANTHROPIC proposal", result.text());
        assertEquals(1, governanceCalls.size());
        assertEquals(result.text(), governanceCalls.getFirst());
        assertEquals(2, result.providerResults().size());
    }

    @Test
    void providerAttributionMismatchIsRejected() {
        IntelligenceFabric fabric = new IntelligenceFabric(
                planner(List.of(LlmProvider.OPENAI)),
                (provider, request) -> response(LlmProvider.GOOGLE, "wrong provider"),
                (request, responses) -> "unused",
                (request, responses, finalText) -> { });

        assertThrows(IllegalStateException.class,
                () -> fabric.execute(request(IntelligenceMode.REASONING, CollaborationMode.SINGLE, 1)));
    }

    private static IntelligenceFabric fabric(List<LlmProvider> providers, IntelligenceGovernance governance) {
        return new IntelligenceFabric(
                planner(providers),
                (provider, request) -> response(provider, provider.name() + " response"),
                (request, responses) -> responses.getFirst().text(),
                governance);
    }

    private static IntelligencePlanner planner(List<LlmProvider> providers) {
        return new IntelligencePlanner(ignored -> providers);
    }

    private static LlmResponse response(LlmProvider provider, String text) {
        return new LlmResponse(provider, "test-model", text, "provider-ref");
    }

    private static IntelligenceRequest request(IntelligenceMode mode, CollaborationMode collaborationMode, int maxProviders) {
        return new IntelligenceRequest(
                "fabric-test",
                "worker-test",
                mode,
                collaborationMode,
                "test objective",
                "test context",
                List.of("evidence:test"),
                "analysis",
                "medium",
                "10s",
                "budget",
                "authority",
                "governed result",
                List.of(),
                maxProviders);
    }
}
