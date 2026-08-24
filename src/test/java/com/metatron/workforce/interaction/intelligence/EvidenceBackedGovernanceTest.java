package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceBackedGovernanceTest {
    @Test
    void governedReasoningRequiresEvidence() {
        IntelligenceRequest request = request(List.of());
        assertThrows(IllegalStateException.class, () -> new EvidenceBackedGovernance().validate(
                request, List.of(response()), "answer"));
    }

    @Test
    void governedReasoningPassesWithEvidence() {
        IntelligenceRequest request = request(List.of("gh:1"));
        assertDoesNotThrow(() -> new EvidenceBackedGovernance().validate(
                request, List.of(response()), "answer"));
    }

    private static LlmResponse response() {
        return new LlmResponse(LlmProvider.OPENAI, "test", "answer", "ref");
    }

    private static IntelligenceRequest request(List<String> evidence) {
        return new IntelligenceRequest("r1", "w1", IntelligenceMode.REASONING,
                CollaborationMode.SINGLE, "audit", "context", evidence,
                "analysis", "medium", "10s", "budget", "authority", "verdict", List.of(), 1);
    }
}
