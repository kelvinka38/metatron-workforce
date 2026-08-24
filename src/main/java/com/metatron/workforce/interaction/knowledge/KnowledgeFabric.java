package com.metatron.workforce.interaction.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral knowledge boundary. Knowledge retrieval precedes scarce intelligence expenditure.
 */
public interface KnowledgeFabric {
    List<KnowledgeItem> retrieve(KnowledgeQuery query);

    record KnowledgeQuery(
            String requester,
            String objective,
            String scope,
            int maxItems) {
        public KnowledgeQuery {
            Objects.requireNonNull(requester, "requester");
            Objects.requireNonNull(objective, "objective");
            Objects.requireNonNull(scope, "scope");
            if (requester.isBlank() || objective.isBlank()) {
                throw new IllegalArgumentException("requester and objective must not be blank");
            }
            if (maxItems < 1) throw new IllegalArgumentException("maxItems must be >= 1");
        }
    }

    record KnowledgeItem(
            String id,
            String source,
            String content,
            String evidenceReference,
            String freshness) {
        public KnowledgeItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
            Objects.requireNonNull(freshness, "freshness");
            if (id.isBlank() || source.isBlank() || content.isBlank()) {
                throw new IllegalArgumentException("knowledge item identity/source/content must not be blank");
            }
        }
    }
}
