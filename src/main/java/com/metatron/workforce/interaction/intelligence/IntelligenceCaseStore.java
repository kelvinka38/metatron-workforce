package com.metatron.workforce.interaction.intelligence;

import java.util.Optional;

/** Channel-neutral persistence boundary for runtime Intelligence Cases. */
public interface IntelligenceCaseStore {
    IntelligenceCase openOrUpdate(String conversationId, String requester, NormalizedRequest normalized);
    void save(IntelligenceCase intelligenceCase);
    Optional<IntelligenceCase> findActive(String conversationId);

    /**
     * Resolves a Case by its stable Case identity so externally owned institutional workflows
     * can correlate Authorization / Execution / Observation / Knowledge results back to the
     * originating analysis without depending on a transport conversation id.
     */
    Optional<IntelligenceCase> findByCaseId(String caseId);
}
