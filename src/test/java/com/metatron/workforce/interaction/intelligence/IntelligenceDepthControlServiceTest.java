package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntelligenceDepthControlServiceTest {
    @TempDir Path temp;

    @Test
    void humanSelectionPersistsAcrossStoreRestartAndAutoClearsIt() {
        String conversation = "conversation:human:primary";
        IntelligenceDepthControlService first = new IntelligenceDepthControlService(
                new PersistentIntelligenceDepthPreferenceStore(temp));

        var selected = first.handle(conversation, "/deep");
        assertTrue(selected.controlHandled());
        assertEquals(IntelligenceDepth.DEEP, selected.contract().selectedDepth());

        IntelligenceDepthControlService restarted = new IntelligenceDepthControlService(
                new PersistentIntelligenceDepthPreferenceStore(temp));
        assertEquals(IntelligenceDepth.DEEP, restarted.contract(conversation).selectedDepth());
        assertTrue(restarted.handle(conversation, "/mode").response().contains("DEEP"));

        restarted.handle(conversation, "/auto");
        assertFalse(restarted.contract(conversation).explicitlySelected());
    }

    @Test
    void ordinaryNaturalLanguageIsNeverKeywordClassifiedAsControl() {
        IntelligenceDepthControlService service = new IntelligenceDepthControlService(
                new PersistentIntelligenceDepthPreferenceStore(temp));
        var result = service.handle("c", "Please do a deep audit of this architecture");
        assertFalse(result.controlHandled());
        assertFalse(result.contract().explicitlySelected());
    }

    @Test
    void selectedDepthOverridesSemanticDepthWithoutChangingModeOrAuthoritySemantics() {
        NormalizedRequest semantic = new NormalizedRequest(
                "analyze", "system", java.util.List.of(), IntelligenceDepth.FAST, "answer",
                java.util.List.of(), java.util.List.of(), "", "", IntelligenceMode.REASONING,
                CollaborationMode.SINGLE, java.util.List.of(), DeterministicCapability.NONE,
                false, null, com.metatron.workforce.interaction.llm.LlmProvider.OPENAI, "");
        NormalizedRequest applied = semantic.withRequestedDepth(
                IntelligenceDepthContract.selected(IntelligenceDepth.DEEP).applyTo(semantic.requestedDepth()));
        assertEquals(IntelligenceDepth.DEEP, applied.requestedDepth());
        assertEquals(IntelligenceMode.REASONING, applied.mode());
    }
}
