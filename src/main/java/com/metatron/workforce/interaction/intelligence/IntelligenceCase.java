package com.metatron.workforce.interaction.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Runtime coordination record for one bounded intelligence problem.
 * External institutional state is referenced only; this object does not own Worker,
 * Meeting, Authorization, Execution, Observation, Outcome or Knowledge state.
 */
public record IntelligenceCase(
        String caseId,
        String conversationId,
        String requester,
        String objective,
        IntelligenceDepth requestedDepth,
        IntelligenceCaseStatus status,
        List<InformationRequirement> informationRequirements,
        List<String> evidenceReferences,
        List<String> assumptions,
        List<String> hypotheses,
        List<String> unknowns,
        List<String> contradictions,
        List<String> reasoningArtifactReferences,
        String latestConclusion,
        String latestRecommendation,
        List<String> externalInstitutionalReferences,
        Instant createdAt,
        Instant updatedAt) {

    public IntelligenceCase {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(requestedDepth, "requestedDepth");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(informationRequirements, "informationRequirements");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        Objects.requireNonNull(assumptions, "assumptions");
        Objects.requireNonNull(hypotheses, "hypotheses");
        Objects.requireNonNull(unknowns, "unknowns");
        Objects.requireNonNull(contradictions, "contradictions");
        Objects.requireNonNull(reasoningArtifactReferences, "reasoningArtifactReferences");
        Objects.requireNonNull(latestConclusion, "latestConclusion");
        Objects.requireNonNull(latestRecommendation, "latestRecommendation");
        Objects.requireNonNull(externalInstitutionalReferences, "externalInstitutionalReferences");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        informationRequirements = List.copyOf(informationRequirements);
        evidenceReferences = List.copyOf(evidenceReferences);
        assumptions = List.copyOf(assumptions);
        hypotheses = List.copyOf(hypotheses);
        unknowns = List.copyOf(unknowns);
        contradictions = List.copyOf(contradictions);
        reasoningArtifactReferences = List.copyOf(reasoningArtifactReferences);
        externalInstitutionalReferences = List.copyOf(externalInstitutionalReferences);
        if (caseId.isBlank() || conversationId.isBlank() || requester.isBlank() || objective.isBlank()) {
            throw new IllegalArgumentException("case identity and objective must not be blank");
        }
    }

    public IntelligenceCase transition(IntelligenceCaseStatus next) {
        return new IntelligenceCase(caseId, conversationId, requester, objective, requestedDepth, next,
                informationRequirements, evidenceReferences, assumptions, hypotheses, unknowns, contradictions,
                reasoningArtifactReferences, latestConclusion, latestRecommendation, externalInstitutionalReferences,
                createdAt, Instant.now());
    }

    public IntelligenceCase withInformationAssessment(List<InformationRequirement> requirements,
                                                      List<String> evidenceRefs,
                                                      IntelligenceCaseStatus nextStatus) {
        return new IntelligenceCase(caseId, conversationId, requester, objective, requestedDepth,
                Objects.requireNonNull(nextStatus, "nextStatus"),
                requirements == null ? informationRequirements : List.copyOf(requirements),
                evidenceRefs == null ? evidenceReferences : List.copyOf(evidenceRefs),
                assumptions, hypotheses, unknowns, contradictions, reasoningArtifactReferences,
                latestConclusion, latestRecommendation, externalInstitutionalReferences,
                createdAt, Instant.now());
    }

    public IntelligenceCase withResult(String conclusion, List<String> evidenceRefs) {
        return new IntelligenceCase(caseId, conversationId, requester, objective, requestedDepth,
                IntelligenceCaseStatus.RESULT_READY, informationRequirements,
                evidenceRefs == null ? evidenceReferences : List.copyOf(evidenceRefs), assumptions, hypotheses,
                unknowns, contradictions, reasoningArtifactReferences,
                conclusion == null ? "" : conclusion, latestRecommendation, externalInstitutionalReferences,
                createdAt, Instant.now());
    }
}
