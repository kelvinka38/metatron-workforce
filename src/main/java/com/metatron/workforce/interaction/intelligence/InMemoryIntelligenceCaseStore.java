package com.metatron.workforce.interaction.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local Case store used by tests and runtimes that do not configure durable persistence. */
public final class InMemoryIntelligenceCaseStore implements IntelligenceCaseStore {
    private final ConcurrentMap<String, IntelligenceCase> activeByConversation = new ConcurrentHashMap<>();
    private final InformationRequirementPlanner requirementPlanner;

    public InMemoryIntelligenceCaseStore() {
        this(new InformationRequirementPlanner(new AnalyticalProtocolRegistry()));
    }

    public InMemoryIntelligenceCaseStore(InformationRequirementPlanner requirementPlanner) {
        this.requirementPlanner = java.util.Objects.requireNonNull(requirementPlanner, "requirementPlanner");
    }

    @Override
    public IntelligenceCase openOrUpdate(String conversationId, String requester, NormalizedRequest normalized) {
        return activeByConversation.compute(conversationId, (key, existing) -> {
            Instant now = Instant.now();
            List<InformationRequirement> requirements = requirementPlanner.plan(normalized);
            if (existing == null || existing.status() == IntelligenceCaseStatus.RESOLVED) {
                return new IntelligenceCase(
                        "case-" + UUID.randomUUID(), conversationId, requester, normalized.objective(),
                        normalized.requestedDepth(), IntelligenceCaseStatus.INFORMATION_ASSESSMENT,
                        requirements, List.of(), normalized.explicitAssumptions(), List.of(),
                        normalized.materiallyAmbiguous() ? List.of(normalized.unresolvedSemanticAmbiguity()) : List.of(),
                        List.of(), List.of(), "", "", List.of(), now, now);
            }
            return new IntelligenceCase(
                    existing.caseId(), existing.conversationId(), existing.requester(), normalized.objective(),
                    normalized.requestedDepth(), IntelligenceCaseStatus.REASSESSMENT,
                    requirements.isEmpty() ? existing.informationRequirements() : requirements,
                    existing.evidenceReferences(),
                    normalized.explicitAssumptions().isEmpty() ? existing.assumptions() : normalized.explicitAssumptions(),
                    existing.hypotheses(),
                    normalized.materiallyAmbiguous() ? List.of(normalized.unresolvedSemanticAmbiguity()) : existing.unknowns(),
                    existing.contradictions(), existing.reasoningArtifactReferences(), existing.latestConclusion(),
                    existing.latestRecommendation(), existing.externalInstitutionalReferences(), existing.createdAt(), now);
        });
    }

    @Override
    public void save(IntelligenceCase intelligenceCase) {
        activeByConversation.put(intelligenceCase.conversationId(), intelligenceCase);
    }

    @Override
    public Optional<IntelligenceCase> findActive(String conversationId) {
        return Optional.ofNullable(activeByConversation.get(conversationId));
    }
}
