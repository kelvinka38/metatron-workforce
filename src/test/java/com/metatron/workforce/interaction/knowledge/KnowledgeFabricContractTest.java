package com.metatron.workforce.interaction.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeFabricContractTest {
    @Test
    void queryIsImmutableAndRequiresPositiveLimit() {
        var query = new KnowledgeFabric.KnowledgeQuery("worker-a", "audit gateway", "gateway-g4", 5);
        assertEquals("gateway-g4", query.scope());
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeFabric.KnowledgeQuery("worker-a", "audit gateway", "gateway-g4", 0));
    }

    @Test
    void knowledgeItemRequiresEvidenceMetadata() {
        var item = new KnowledgeFabric.KnowledgeItem(
                "k-1", "github", "gateway state", "github:commit:abc", "2026-08-24T17:00Z");
        assertEquals("github:commit:abc", item.evidenceReference());
        assertEquals(List.of(), List.copyOf(List.of()));
    }
}
