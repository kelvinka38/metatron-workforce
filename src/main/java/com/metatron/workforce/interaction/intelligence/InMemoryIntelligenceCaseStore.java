package com.metatron.workforce.interaction.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local Case store used by tests and runtimes that do not configure durable persistence. */
public final class InMemoryIntelligenceCaseStore implements IntelligenceCaseStore {
    private final ConcurrentMap<String, String> activeCaseByConversation = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IntelligenceCase> casesById = new ConcurrentHashMap<>();
    private final InformationRequirementPlanner requirementPlanner;

    public InMemoryIntelligenceCaseStore() {
        this(new InformationRequirementPlanner(new AnalyticalProtocolRegistry()));
    }

    public InMemoryIntelligenceCaseStore(InformationRequirementPlanner requirementPlanner) {
        this.requirementPlanner = java.util.Objects.requireNonNull(requirementPlanner, "requirementPlanner");
    }

    @Override
    public synchronized IntelligenceCase openOrUpdate(String conversationId, String requester, NormalizedRequest normalized) {
        if (conversationId == null || conversationId.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");
        java.util.Objects.requireNonNull(requester, "requester");
        java.util.Objects.requireNonNull(normalized, "normalized");

        Instant now = Instant.now();
        List<InformationRequirement> requirements = requirementPlanner.plan(normalized);
        IntelligenceCase existing = findActive(conversationId).orElse(null);
        IntelligenceCase next;
        if (existing == null || existing.status() == IntelligenceCaseStatus.RESOLVED) {
            next = new IntelligenceCase(
                    "case-" + UUID.randomUUID(), conversationId, requester, normalized.objective(),
                    normalized.requestedDepth(), IntelligenceCaseStatus.INFORMATION_ASSESSMENT,
                    requirements, List.of(), normalized.explicitAssumptions(), List.of(),
                    normalized.materiallyAmbiguous() ? List.of(normalized.unresolvedSemanticAmbiguity()) : List.of(),
                    List.of(), List.of(), "", "", List.of(), now, now);
        } else {
            next = new IntelligenceCase(
                    existing.caseId(), existing.conversationId(), existing.requester(), normalized.objective(),
                    normalized.requestedDepth(), IntelligenceCaseStatus.REASSESSMENT,
                    requirements.isEmpty() ? existing.informationRequirements() : requirements,
                    existing.evidenceReferences(),
                    normalized.explicitAssumptions().isEmpty() ? existing.assumptions() : normalized.explicitAssumptions(),
                    existing.hypotheses(),
                    normalized.materiallyAmbiguous() ? List.of(normalized.unresolvedSemanticAmbiguity()) : existing.unknowns(),
                    existing.contradictions(), existing.reasoningArtifactReferences(), existing.latestConclusion(),
                    existing.latestRecommendation(), existing.externalInstitutionalReferences(), existing.createdAt(), now);
        }
        save(next);
        return next;
    }

    @Override
    public synchronized void save(IntelligenceCase intelligenceCase) {
        java.util.Objects.requireNonNull(intelligenceCase, "intelligenceCase");
        casesById.put(intelligenceCase.caseId(), intelligenceCase);
        activeCaseByConversation.put(intelligenceCase.conversationId(), intelligenceCase.caseId());
    }

    @Override
    public synchronized Optional<IntelligenceCase> findActive(String conversationId) {
        String caseId = activeCaseByConversation.get(conversationId);
        return caseId == null ? Optional.empty() : Optional.ofNullable(casesById.get(caseId));
    }

    @Override
    public synchronized Optional<IntelligenceCase> findByCaseId(String caseId) {
        if (caseId == null || caseId.isBlank()) throw new IllegalArgumentException("caseId must not be blank");
        return Optional.ofNullable(casesById.get(caseId));
    }
}
