package com.metatron.workforce.interaction.intelligence;

import java.util.Optional;

/** Channel-neutral persistence boundary for runtime Intelligence Cases. */
public interface IntelligenceCaseStore {
    IntelligenceCase openOrUpdate(String conversationId, String requester, NormalizedRequest normalized);
    void save(IntelligenceCase intelligenceCase);
    Optional<IntelligenceCase> findActive(String conversationId);
}
