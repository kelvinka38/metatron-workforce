package com.metatron.workforce.interaction.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class KnowledgeFabricSourceAdapterTest {
    @Test
    void preservesKnowledgeEvidenceReferencesWithoutPerformingAdmission() {
        KnowledgeFabric fabric = query -> List.of(
                new KnowledgeFabric.KnowledgeItem(
                        "k-1", "canonical-knowledge", "approved control definition",
                        "knowledge:control-1", "2026-08-30T00:00:00Z"),
                new KnowledgeFabric.KnowledgeItem(
                        "k-2", "canonical-knowledge", "approved evidence rule",
                        "knowledge:rule-2", "2026-08-30T00:00:00Z"));
        KnowledgeFabricSourceAdapter adapter = new KnowledgeFabricSourceAdapter(fabric, "intelligence:runtime");

        KnowledgeDocument document = adapter.retrieve(new KnowledgeQuery(
                "audit control", "gateway", List.of(), 5));

        assertNotNull(document);
        assertEquals("knowledge.fabric", document.sourceId());
        assertTrue(document.evidenceReferences().contains("knowledge:control-1"));
        assertTrue(document.evidenceReferences().contains("knowledge:rule-2"));
        assertTrue(document.content().contains("approved control definition"));
    }
}
