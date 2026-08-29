package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidencePreservingMultiModelSynthesizerTest {
    private final EvidencePreservingMultiModelSynthesizer synthesizer = new EvidencePreservingMultiModelSynthesizer();

    @Test
    void consensusPreservesDivergenceAndProviderAttributionWithoutMajorityTruth() {
        String result = synthesizer.synthesize(
                request(CollaborationMode.CONSENSUS),
                List.of(
                        response(LlmProvider.OPENAI, "proposal A"),
                        response(LlmProvider.ANTHROPIC, "proposal B")));

        assertTrue(result.contains("status=DIVERGENT_OUTPUTS"));
        assertTrue(result.contains("OPENAI"));
        assertTrue(result.contains("ANTHROPIC"));
        assertTrue(result.contains("proposal A"));
        assertTrue(result.contains("proposal B"));
        assertTrue(result.contains("not evidence or truth"));
    }

    @Test
    void leadReviewKeepsLeadAndReviewerDistinct() {
        String result = synthesizer.synthesize(
                request(CollaborationMode.LEAD_REVIEW),
                List.of(
                        response(LlmProvider.OPENAI, "lead answer"),
                        response(LlmProvider.GOOGLE, "review answer")));

        assertTrue(result.contains("LEAD [OPENAI"));
        assertTrue(result.contains("REVIEWER 1 [GOOGLE"));
        assertTrue(result.contains("lead answer"));
        assertTrue(result.contains("review answer"));
        assertTrue(result.contains("does not create authority"));
    }

    @Test
    void refusesSingleModeAndInsufficientIndependentResponses() {
        assertThrows(IllegalStateException.class, () -> synthesizer.synthesize(
                request(CollaborationMode.SINGLE),
                List.of(response(LlmProvider.OPENAI, "only"), response(LlmProvider.GOOGLE, "other"))));
        assertThrows(IllegalStateException.class, () -> synthesizer.synthesize(
                request(CollaborationMode.CONSENSUS),
                List.of(response(LlmProvider.OPENAI, "only"))));
    }

    private static LlmResponse response(LlmProvider provider, String text) {
        return new LlmResponse(provider, "test-model", text, "provider-ref");
    }

    private static IntelligenceRequest request(CollaborationMode mode) {
        return new IntelligenceRequest(
                "synthesis-test", "worker-test", IntelligenceMode.REASONING, mode,
                "compare independent proposals", "context", List.of("evidence:test"),
                "analysis", "MEDIUM", "10s", "standard", "", "review",
                List.of(), mode == CollaborationMode.SINGLE ? 1 : 3);
    }
}
