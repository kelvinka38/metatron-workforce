package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class IntelligenceDepthConsequenceSeparationTest {
    @Test
    void deepReasoningDoesNotManufactureHighInstitutionalConsequence() throws Exception {
        Method consequence = MetatronIntelligenceResponder.class.getDeclaredMethod("consequence", NormalizedRequest.class);
        consequence.setAccessible(true);

        NormalizedRequest deep = normalized(IntelligenceDepth.DEEP, IntelligenceMode.REASONING);
        NormalizedRequest analyze = normalized(IntelligenceDepth.ANALYZE, IntelligenceMode.REASONING);
        NormalizedRequest fast = normalized(IntelligenceDepth.FAST, IntelligenceMode.DISCUSSION);

        assertEquals("MEDIUM", consequence.invoke(null, deep));
        assertEquals("MEDIUM", consequence.invoke(null, analyze));
        assertEquals("LOW", consequence.invoke(null, fast));
    }

    private static NormalizedRequest normalized(IntelligenceDepth depth, IntelligenceMode mode) {
        return new NormalizedRequest(
                "analyze evidence", "", List.of(), depth, "answer", List.of(), List.of(), "", "",
                mode, CollaborationMode.SINGLE, List.of(AnalyticalProtocolType.AUDIT),
                DeterministicCapability.NONE, false, null, LlmProvider.GOOGLE, "");
    }
}
