package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PersistentIntelligenceCaseStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void survivesStoreRecreationAndResumesTheSameActiveCase() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PersistentIntelligenceCaseStore firstStore = new PersistentIntelligenceCaseStore(tempDir, mapper);

        IntelligenceCase opened = firstStore.openOrUpdate(
                "conversation:durable", "human:1", normalized("analyze revenue decline", IntelligenceDepth.ANALYZE));
        firstStore.save(opened.withResult("Initial conclusion", List.of("evidence:1")));

        PersistentIntelligenceCaseStore restartedStore = new PersistentIntelligenceCaseStore(tempDir, mapper);
        IntelligenceCase loaded = restartedStore.findActive("conversation:durable").orElseThrow();

        assertEquals(opened.caseId(), loaded.caseId());
        assertEquals("Initial conclusion", loaded.latestConclusion());
        assertEquals(List.of("evidence:1"), loaded.evidenceReferences());

        IntelligenceCase resumed = restartedStore.openOrUpdate(
                "conversation:durable", "human:1", normalized("continue root-cause analysis", IntelligenceDepth.DEEP));

        assertEquals(opened.caseId(), resumed.caseId());
        assertEquals(IntelligenceCaseStatus.REASSESSMENT, resumed.status());
        assertEquals(IntelligenceDepth.DEEP, resumed.requestedDepth());
        assertEquals("Initial conclusion", resumed.latestConclusion());
        assertEquals(List.of("evidence:1"), resumed.evidenceReferences());
    }

    @Test
    void resolvesStableCaseIdAcrossStoreRecreation() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PersistentIntelligenceCaseStore firstStore = new PersistentIntelligenceCaseStore(tempDir, mapper);
        IntelligenceCase opened = firstStore.openOrUpdate(
                "conversation:institutional-correlation", "human:1",
                normalized("assess execution outcome", IntelligenceDepth.ANALYZE));

        PersistentIntelligenceCaseStore restartedStore = new PersistentIntelligenceCaseStore(tempDir, mapper);
        IntelligenceCase byCase = restartedStore.findByCaseId(opened.caseId()).orElseThrow();

        assertEquals(opened.caseId(), byCase.caseId());
        assertEquals(opened.conversationId(), byCase.conversationId());
        assertTrue(Files.isDirectory(tempDir.resolve("cases")));
        assertTrue(Files.isDirectory(tempDir.resolve("by-conversation")));
    }

    @Test
    void retainsResolvedHistoricalCaseWhenConversationStartsANewCase() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PersistentIntelligenceCaseStore store = new PersistentIntelligenceCaseStore(tempDir, mapper);
        IntelligenceCase first = store.openOrUpdate(
                "conversation:history", "human:1", normalized("first objective", IntelligenceDepth.ANALYZE));
        store.save(first.transition(IntelligenceCaseStatus.RESOLVED));

        IntelligenceCase second = store.openOrUpdate(
                "conversation:history", "human:1", normalized("second objective", IntelligenceDepth.DEEP));

        assertNotEquals(first.caseId(), second.caseId());
        assertEquals(second.caseId(), store.findActive("conversation:history").orElseThrow().caseId());
        assertEquals(first.caseId(), store.findByCaseId(first.caseId()).orElseThrow().caseId());
        assertEquals(IntelligenceCaseStatus.RESOLVED, store.findByCaseId(first.caseId()).orElseThrow().status());
    }

    private static NormalizedRequest normalized(String objective, IntelligenceDepth depth) {
        return new NormalizedRequest(objective, "", List.of(), depth, "direct natural-language answer",
                List.of(), List.of(), "", "", IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.ROOT_CAUSE), DeterministicCapability.NONE, false,
                null, LlmProvider.GOOGLE, "");
    }
}
