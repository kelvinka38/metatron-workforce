package com.metatron.workforce.interaction.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapts the provider-neutral KnowledgeFabric contract into the Intelligence retrieval pipeline.
 * The adapter does not perform Knowledge admission; it only reads items already exposed by KnowledgeFabric.
 */
public final class KnowledgeFabricSourceAdapter implements KnowledgeSource {
    private final KnowledgeFabric fabric;
    private final String requester;

    public KnowledgeFabricSourceAdapter(KnowledgeFabric fabric, String requester) {
        this.fabric = Objects.requireNonNull(fabric, "fabric");
        this.requester = Objects.requireNonNull(requester, "requester").trim();
        if (this.requester.isBlank()) throw new IllegalArgumentException("requester must not be blank");
    }

    @Override
    public String sourceId() {
        return "knowledge.fabric";
    }

    @Override
    public KnowledgeDocument retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query");
        List<KnowledgeFabric.KnowledgeItem> items = fabric.retrieve(new KnowledgeFabric.KnowledgeQuery(
                requester,
                query.query(),
                query.scope(),
                query.maxResults()));
        if (items == null || items.isEmpty()) return null;

        StringBuilder content = new StringBuilder();
        List<String> refs = new ArrayList<>();
        StringBuilder title = new StringBuilder("KnowledgeFabric retrieval");
        int count = 0;
        for (KnowledgeFabric.KnowledgeItem item : items) {
            if (item == null || item.content().isBlank()) continue;
            if (count++ >= query.maxResults()) break;
            refs.add(item.evidenceReference());
            content.append("KNOWLEDGE ITEM\n")
                    .append("id=").append(item.id()).append('\n')
                    .append("source=").append(item.source()).append('\n')
                    .append("freshness=").append(item.freshness()).append('\n')
                    .append("evidence_ref=").append(item.evidenceReference()).append('\n')
                    .append("content=\n").append(item.content()).append("\n\n");
        }
        if (content.isEmpty()) return null;
        return new KnowledgeDocument(
                sourceId() + ":" + Integer.toUnsignedString((query.query() + query.scope()).hashCode()),
                sourceId(),
                title.toString(),
                content.toString().trim(),
                List.copyOf(refs));
    }
}
