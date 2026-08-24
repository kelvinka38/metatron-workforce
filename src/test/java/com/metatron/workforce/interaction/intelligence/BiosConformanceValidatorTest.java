package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BiosConformanceValidatorTest {
    private final BiosConformanceValidator validator = new BiosConformanceValidator();

    @Test
    void casualConversationDoesNotRequireGovernedEvidence() {
        assertDoesNotThrow(() -> validator.validate(
                request(IntelligenceMode.CASUAL, "LOW", "worker:metatron", ""),
                List.of(response()),
                "hello"));
    }

    @Test
    void reasoningRequiresEvidence() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", "worker:metatron", ""),
                List.of(response()),
                "analysis"));
    }

    @Test
    void reasoningRejectsPlaceholderEvidence() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", "worker:metatron", "unknown"),
                List.of(response()),
                "analysis"));
    }

    @Test
    void decisionRequiresAuthority() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.DECISION, "MEDIUM", "", "evidence:1"),
                List.of(response()),
                "recommendation"));
    }

    @Test
    void highConsequenceCannotRunAtCasualGovernance() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.CASUAL, "CRITICAL", "worker:metatron", ""),
                List.of(response()),
                "answer"));
    }

    @Test
    void validGovernedReasoningPasses() {
        assertDoesNotThrow(() -> validator.validate(
                request(IntelligenceMode.REASONING, "HIGH", "worker:metatron", "evidence:gateway-g4"),
                List.of(response()),
                "audit result"));
    }

    @Test
    void duplicateEvidenceIsRejected() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", "worker:metatron", "evidence:1", "evidence:1"),
                List.of(response()),
                "analysis"));
    }

    @Test
    void duplicateProviderAttributionIsRejected() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", "worker:metatron", "evidence:1"),
                List.of(response(), response()),
                "analysis"));
    }

    @Test
    void criticalConsequenceRequiresMultiEngineReview() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.DECISION, "CRITICAL", "human:authorized", "evidence:critical-1"),
                List.of(response()),
                "decision support"));
    }

    @Test
    void criticalConsequencePassesWithDistinctEnginesAndAuthority() {
        assertDoesNotThrow(() -> validator.validate(
                request(IntelligenceMode.DECISION, "CRITICAL", "human:authorized", "evidence:critical-1"),
                List.of(response(), new LlmResponse(LlmProvider.ANTHROPIC, "test-model-2", "independent review", "provider-request-2")),
                "decision support"));
    }

    private static IntelligenceRequest request(
            IntelligenceMode mode,
            String consequence,
            String authority,
            String... evidence) {
        CollaborationMode collaboration = "CRITICAL".equals(consequence)
                ? CollaborationMode.PARALLEL_SYNTHESIS
                : CollaborationMode.SINGLE;
        int maxProviders = "CRITICAL".equals(consequence) ? 2 : 1;
        return new IntelligenceRequest(
                "test-request",
                "test-requester",
                mode,
                collaboration,
                "test objective",
                "test context",
                List.of(evidence),
                "analysis",
                consequence,
                "standard",
                "standard",
                authority,
                "test output",
                List.of(),
                maxProviders);
    }

    private static LlmResponse response() {
        return new LlmResponse(LlmProvider.OPENAI, "test-model", "test response", "provider-request-1");
    }
}
