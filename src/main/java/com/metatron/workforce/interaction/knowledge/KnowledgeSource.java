package com.metatron.workforce.interaction.knowledge;

/** A source that can supply grounded information to a worker. */
public interface KnowledgeSource {
    String sourceId();
    KnowledgeDocument retrieve(KnowledgeQuery query);
}
