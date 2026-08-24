package com.metatron.workforce.interaction.knowledge;

import java.util.List;
import java.util.Objects;

public record KnowledgeQuery(String query, String scope, List<String> preferredSources, int maxResults) {
    public KnowledgeQuery {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(preferredSources, "preferredSources");
        preferredSources = List.copyOf(preferredSources);
        if (query.isBlank()) throw new IllegalArgumentException("query must not be blank");
        if (maxResults < 1) throw new IllegalArgumentException("maxResults must be >= 1");
    }
}
