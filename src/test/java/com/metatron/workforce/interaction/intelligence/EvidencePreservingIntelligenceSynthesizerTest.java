package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidencePreservingIntelligenceSynthesizerTest {
    private final EvidencePreservingIntelligenceSynthesizer synthesizer = new EvidencePreservingIntelligenceSynthesizer();

    @Test
    void parallelSynthesisPreservesAttributionAndDoesNotClaimConsensusTruth() {
        String result = synthesizer.synthesize(request(CollaborationMode.CONSENSUS), List.of(
                response(LlmProvider.OPENAI, "proposal-a"),
                response(LlmProvider.ANTHROPIC, "proposal-b")));
        assertTrue(result.contains("OPENAI"));
        assertTrue(result.contains("ANTHROPIC"));
        assertTrue(result.contains("proposal-a"));
        assertTrue(result.contains("proposal-b"));
        assertTrue(result.contains("agreement is not evidence"));
        assertTrue(result.contains("neither institutional truth nor authority"));
    }

    @Test
    void leadReviewPreservesLeadAndReviewer() {
        String result = synthesizer.synthesize(request(CollaborationMode.LEAD_REVIEW), List.of(
                response(LlmProvider.OPENAI, "lead"), response(LlmProvider.GOOGLE, "review")));
        assertTrue(result.contains("LEAD [OPENAI]"));
        assertTrue(result.contains("REVIEWER 1 [GOOGLE]"));
    }

    @Test
    void independentSecondOpinionPreservesIndependence() {
        String result = synthesizer.synthesize(request(CollaborationMode.INDEPENDENT_SECOND_OPINION), List.of(
                response(LlmProvider.OPENAI, "primary"), response(LlmProvider.ANTHROPIC, "second")));
        assertTrue(result.contains("PRIMARY [OPENAI]"));
        assertTrue(result.contains("SECOND OPINION [ANTHROPIC]"));
    }

    @Test
    void adversarialReviewPreservesChallenge() {
        String result = synthesizer.synthesize(request(CollaborationMode.ADVERSARIAL_REVIEW), List.of(
                response(LlmProvider.GOOGLE, "proposal"), response(LlmProvider.ANTHROPIC, "challenge")));
        assertTrue(result.contains("PROPOSAL [GOOGLE]"));
        assertTrue(result.contains("CHALLENGE 1 [ANTHROPIC]"));
    }

    @Test
    void refusesSingleResponseOrSingleMode() {
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(
                request(CollaborationMode.CONSENSUS), List.of(response(LlmProvider.OPENAI, "only"))));
        assertThrows(IllegalArgumentException.class, () -> synthesizer.synthesize(
                request(CollaborationMode.SINGLE), List.of(response(LlmProvider.OPENAI, "a"), response(LlmProvider.GOOGLE, "b"))));
    }

    private static LlmResponse response(LlmProvider provider, String text) {
        return new LlmResponse(provider, "test-model", text, "provider-ref-" + provider);
    }

    private static IntelligenceRequest request(CollaborationMode mode) {
        return new IntelligenceRequest("synthesis-test", "worker-test", IntelligenceMode.REASONING, mode,
                "review objective", "context", List.of("evidence:test"), "analysis", "medium", "10s", "budget", "",
                "governed review", List.of(), mode == CollaborationMode.SINGLE ? 1 : 3);
    }
}
