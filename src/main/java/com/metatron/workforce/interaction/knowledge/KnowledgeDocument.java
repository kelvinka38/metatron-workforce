package com.metatron.workforce.interaction.knowledge;

import java.util.List;
import java.util.Objects;

public record KnowledgeDocument(
        String documentId,
        String sourceId,
        String title,
        String content,
        List<String> evidenceReferences) {
    public KnowledgeDocument {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        evidenceReferences = List.copyOf(evidenceReferences);
    }
}
