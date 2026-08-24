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
                request(IntelligenceMode.CASUAL, "LOW", ""),
                List.of(response()),
                "hello"));
    }

    @Test
    void reasoningRequiresEvidence() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", ""),
                List.of(response()),
                "analysis"));
    }

    @Test
    void reasoningRejectsPlaceholderEvidence() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", "unknown"),
                List.of(response()),
                "analysis"));
    }

    @Test
    void decisionRequiresAuthority() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.DECISION, "MEDIUM", "evidence:1"),
                List.of(response()),
                "recommendation"));
    }

    @Test
    void highConsequenceCannotRunAtCasualGovernance() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.CASUAL, "CRITICAL", ""),
                List.of(response()),
                "answer"));
    }

    @Test
    void validGovernedReasoningPasses() {
        assertDoesNotThrow(() -> validator.validate(
                request(IntelligenceMode.REASONING, "HIGH", "evidence:gateway-g4"),
                List.of(response()),
                "audit result"));
    }

    @Test
    void duplicateEvidenceIsRejected() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", "evidence:1", "evidence:1"),
                List.of(response()),
                "analysis"));
    }

    @Test
    void duplicateProviderAttributionIsRejected() {
        assertThrows(IllegalStateException.class, () -> validator.validate(
                request(IntelligenceMode.REASONING, "LOW", "evidence:1"),
                List.of(response(), response()),
                "analysis"));
    }

    private static IntelligenceRequest request(IntelligenceMode mode, String consequence, String... evidence) {
        String authority = mode.ordinal() >= IntelligenceMode.DECISION.ordinal() ? "authority:human" : "worker:metatron";
        return new IntelligenceRequest(
                "test-request",
                "test-requester",
                mode,
                CollaborationMode.SINGLE,
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
                1);
    }

    private static LlmResponse response() {
        return new LlmResponse(LlmProvider.OPENAI, "test-model", "test response", "provider-request-1");
    }
}
