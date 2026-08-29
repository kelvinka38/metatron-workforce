package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class InMemoryIntelligenceCaseStoreTest {
    @Test
    void reusesOneActiveCaseAcrossConversationFollowUpsWithoutOwningExternalInstitutionalState() {
        InMemoryIntelligenceCaseStore store = new InMemoryIntelligenceCaseStore();
        NormalizedRequest first = normalized("analyze revenue decline", IntelligenceDepth.ANALYZE);
        IntelligenceCase opened = store.openOrUpdate("conversation:1", "human:1", first);
        store.save(opened.withResult("Initial conclusion", List.of("evidence:1")));

        NormalizedRequest followUp = normalized("continue root-cause analysis", IntelligenceDepth.DEEP);
        IntelligenceCase resumed = store.openOrUpdate("conversation:1", "human:1", followUp);

        assertEquals(opened.caseId(), resumed.caseId());
        assertEquals(IntelligenceCaseStatus.REASSESSMENT, resumed.status());
        assertEquals(IntelligenceDepth.DEEP, resumed.requestedDepth());
        assertEquals("Initial conclusion", resumed.latestConclusion());
        assertEquals(List.of("evidence:1"), resumed.evidenceReferences());
        assertFalse(resumed.informationRequirements().isEmpty());
        assertTrue(resumed.externalInstitutionalReferences().isEmpty());
    }

    private static NormalizedRequest normalized(String objective, IntelligenceDepth depth) {
        return new NormalizedRequest(objective, "", List.of(), depth, "direct natural-language answer",
                List.of(), List.of(), "", "", IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.ROOT_CAUSE), DeterministicCapability.NONE, false,
                null, LlmProvider.GOOGLE, "");
    }
}
