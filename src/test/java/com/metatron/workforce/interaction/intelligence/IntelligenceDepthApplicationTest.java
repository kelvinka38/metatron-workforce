package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntelligenceDepthApplicationTest {
    private static NormalizedRequest request(IntelligenceDepth depth) {
        return new NormalizedRequest("objective", "target", List.of(), depth, "answer", List.of(), List.of(),
                "", "", IntelligenceMode.REASONING, CollaborationMode.SINGLE, List.of(),
                DeterministicCapability.NONE, false, null, LlmProvider.OPENAI, "");
    }

    @Test void autoPreservesSemanticSelection() {
        assertEquals(IntelligenceDepth.ANALYZE,
                IntelligenceDepthApplication.apply(request(IntelligenceDepth.ANALYZE), IntelligenceDepthContract.automatic()).requestedDepth());
    }

    @Test void humanSelectionOverridesOnlyDepth() {
        NormalizedRequest original = request(IntelligenceDepth.FAST);
        NormalizedRequest applied = IntelligenceDepthApplication.apply(original, IntelligenceDepthContract.selected(IntelligenceDepth.DEEP));
        assertEquals(IntelligenceDepth.DEEP, applied.requestedDepth());
        assertEquals(original.mode(), applied.mode());
        assertEquals(original.objective(), applied.objective());
    }
}
