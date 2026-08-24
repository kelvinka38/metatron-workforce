package com.metatron.workforce.interaction.evidence;

import com.metatron.workforce.interaction.knowledge.KnowledgeDocument;
import com.metatron.workforce.interaction.tools.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts grounded source/tool outputs into one evidence bundle. */
public final class EvidenceCollector {
    public EvidenceBundle collect(List<KnowledgeDocument> documents, List<ToolResult> toolResults) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(toolResults, "toolResults");
        List<Evidence> items = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            if (document.content().isBlank()) continue;
            List<String> refs = document.evidenceReferences();
            String reference = refs.isEmpty() ? document.documentId() : refs.getFirst();
            items.add(new Evidence(document.sourceId(), reference, document.content(), Instant.now()));
        }
        for (ToolResult result : toolResults) {
            if (!result.success() || result.output().isBlank()) continue;
            String reference = result.evidenceReferences().isEmpty()
                    ? result.requestId() : result.evidenceReferences().getFirst();
            items.add(new Evidence(result.capability(), reference, result.output(), Instant.now()));
        }
        return new EvidenceBundle(items);
    }
}
