package com.metatron.workforce.interaction.knowledge;

import java.util.List;
import java.util.Objects;

/** Executes grounded retrieval before an intelligence request is planned. */
public final class KnowledgeRetrievalService {
    private final List<KnowledgeSource> sources;

    public KnowledgeRetrievalService(List<KnowledgeSource> sources) {
        Objects.requireNonNull(sources, "sources");
        this.sources = List.copyOf(sources);
    }

    public List<KnowledgeDocument> retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query");
        return sources.stream()
                .filter(source -> query.preferredSources().isEmpty()
                        || query.preferredSources().contains(source.sourceId()))
                .map(source -> source.retrieve(query))
                .filter(Objects::nonNull)
                .limit(query.maxResults())
                .toList();
    }
}
