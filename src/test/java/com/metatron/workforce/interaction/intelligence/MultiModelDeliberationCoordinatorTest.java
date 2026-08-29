package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolAdapter;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class MultiModelDeliberationCoordinatorTest {
    @Test
    void preservesIndependentRoundThenAcquiresEvidenceAndRunsOneTargetedChallengeRound() {
        AtomicInteger normalizations = new AtomicInteger();
        AtomicInteger challenges = new AtomicInteger();
        AtomicInteger webCalls = new AtomicInteger();

        IntelligenceEngine engine = (provider, request) -> {
            if (request.requiredCapability().equals("deliberation-normalization")) {
                normalizations.incrementAndGet();
                return new LlmResponse(provider, "test", """
                        {
                          "material_disagreement":true,
                          "disputed_questions":["What is the current market value?"],
                          "unsupported_claims":["provider claim without source"],
                          "missing_evidence":["current market source"],
                          "external_evidence_query":"current market value official source",
                          "unresolved_disagreement":"valuation differs"
                        }
                        """, "normalize-ref");
            }
            if (request.requiredCapability().equals("targeted-challenge")) {
                challenges.incrementAndGet();
                assertTrue(request.context().contains("ADDITIONAL GROUNDING EVIDENCE"));
                return new LlmResponse(provider, "test", "revised-" + provider, "challenge-" + provider);
            }
            throw new IllegalStateException("unexpected capability " + request.requiredCapability());
        };

        ToolAdapter web = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                webCalls.incrementAndGet();
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                        true, "official current value=42", List.of("https://example.test/official"));
            }
        };

        MultiModelDeliberationCoordinator coordinator = new MultiModelDeliberationCoordinator(
                engine, new DefaultToolFabric(List.of(web)), new ObjectMapper());
        IntelligenceRequest request = request();
        List<LlmResponse> independent = List.of(
                new LlmResponse(LlmProvider.GOOGLE, "g", "initial google", "g-ref"),
                new LlmResponse(LlmProvider.ANTHROPIC, "a", "initial anthropic", "a-ref"));

        var outcome = coordinator.deliberate(request, independent);

        assertEquals(independent, outcome.independentResponses());
        assertEquals(1, normalizations.get());
        assertEquals(1, webCalls.get());
        assertEquals(2, challenges.get());
        assertTrue(outcome.challengeRoundExecuted());
        assertEquals(List.of("https://example.test/official"), outcome.addedEvidenceReferences());
        assertEquals("revised-GOOGLE", outcome.responsesForSynthesis().getFirst().text());
        assertEquals("revised-ANTHROPIC", outcome.responsesForSynthesis().get(1).text());
        assertTrue(coordinator.renderPrelude(outcome).contains("Consensus" ) == false);
        assertTrue(coordinator.renderPrelude(outcome).contains("material_disagreement=true"));
    }

    @Test
    void stopsAfterNormalizationWhenNoMaterialDisagreementExists() {
        AtomicInteger challenges = new AtomicInteger();
        IntelligenceEngine engine = (provider, request) -> {
            if (request.requiredCapability().equals("deliberation-normalization")) {
                return new LlmResponse(provider, "test", """
                        {
                          "material_disagreement":false,
                          "disputed_questions":[],
                          "unsupported_claims":[],
                          "missing_evidence":[],
                          "external_evidence_query":"",
                          "unresolved_disagreement":""
                        }
                        """, "normalize-ref");
            }
            challenges.incrementAndGet();
            throw new IllegalStateException("challenge should not run");
        };
        ToolAdapter web = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) { throw new AssertionError("web should not run"); }
        };
        MultiModelDeliberationCoordinator coordinator = new MultiModelDeliberationCoordinator(
                engine, new DefaultToolFabric(List.of(web)), new ObjectMapper());
        List<LlmResponse> independent = List.of(
                new LlmResponse(LlmProvider.GOOGLE, "g", "same conclusion one", "g-ref"),
                new LlmResponse(LlmProvider.ANTHROPIC, "a", "same conclusion two", "a-ref"));

        var outcome = coordinator.deliberate(request(), independent);

        assertFalse(outcome.challengeRoundExecuted());
        assertEquals(independent, outcome.responsesForSynthesis());
        assertEquals(0, challenges.get());
    }

    private static IntelligenceRequest request() {
        return new IntelligenceRequest("req-1", "human:1", IntelligenceMode.REASONING,
                CollaborationMode.ADVERSARIAL_REVIEW, "assess investment", "context", List.of("evidence:base"),
                "analysis", "HIGH", "extended", "deep", "", "analysis", List.of(), 2, false);
    }
}
