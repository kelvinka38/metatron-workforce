package com.metatron.workforce.interaction.evidence;

import com.metatron.workforce.interaction.knowledge.KnowledgeDocument;
import com.metatron.workforce.interaction.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceCollectorTest {
    @Test
    void collectsKnowledgeAndSuccessfulToolEvidence() {
        EvidenceCollector collector = new EvidenceCollector();
        EvidenceBundle bundle = collector.collect(
                List.of(new KnowledgeDocument("doc-1", "github", "repo", "state", List.of("gh:1"))),
                List.of(new ToolResult("req-1", "cloudflare.read", "g4", "get", true, "live-state", List.of("cf:1"))));
        assertEquals(2, bundle.items().size());
        assertTrue(bundle.items().stream().anyMatch(e -> e.reference().equals("gh:1")));
        assertTrue(bundle.items().stream().anyMatch(e -> e.reference().equals("cf:1")));
    }

    @Test
    void ignoresFailedOrEmptyObservations() {
        EvidenceCollector collector = new EvidenceCollector();
        EvidenceBundle bundle = collector.collect(
                List.of(new KnowledgeDocument("doc-1", "github", "repo", "", List.of())),
                List.of(new ToolResult("req-1", "cloudflare.read", "g4", "get", false, "failed", List.of())));
        assertTrue(bundle.isEmpty());
    }
}
