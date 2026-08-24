package com.metatron.workforce.interaction.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeRetrievalServiceTest {
    @Test
    void retrievesOnlySelectedSourcesAndPreservesEvidence() {
        KnowledgeSource source = new KnowledgeSource() {
            public String sourceId() { return "github"; }
            public KnowledgeDocument retrieve(KnowledgeQuery query) {
                return new KnowledgeDocument("doc-1", sourceId(), "Gateway", "current state", List.of("github://commit/abc"));
            }
        };
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(List.of(source));

        var result = service.retrieve(new KnowledgeQuery("audit G4", "gateway", List.of("github"), 5));

        assertEquals(1, result.size());
        assertEquals("github://commit/abc", result.getFirst().evidenceReferences().getFirst());
    }
}
