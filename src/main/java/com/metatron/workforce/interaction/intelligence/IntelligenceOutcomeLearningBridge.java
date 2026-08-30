package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase8.Experience;
import com.metatron.workforce.phase8.LearningEvidence;
import com.metatron.workforce.phase8.LearningService;
import com.metatron.workforce.phase9.BoundaryResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Connects observed external outcomes back into Workforce learning and an Intelligence Case.
 * Observation/outcome truth remains external; this bridge requires successful Observation boundary evidence.
 */
public final class IntelligenceOutcomeLearningBridge {
    private final LearningService learning;
    private final InstitutionalIntelligenceReferenceBridge references;

    public IntelligenceOutcomeLearningBridge(LearningService learning) {
        this(learning, new InstitutionalIntelligenceReferenceBridge());
    }

    IntelligenceOutcomeLearningBridge(LearningService learning, InstitutionalIntelligenceReferenceBridge references) {
        this.learning = Objects.requireNonNull(learning, "learning");
        this.references = Objects.requireNonNull(references, "references");
    }

    public FeedbackResult recordObservedOutcome(
            IntelligenceCase intelligenceCase,
            BoundaryResult observationBoundary,
            String executionId,
            String observationId,
            String outcomeId,
            String sourceReference,
            String expectedVsActualStatement,
            Instant observedAt) {

        Objects.requireNonNull(intelligenceCase, "intelligenceCase");
        Objects.requireNonNull(observationBoundary, "observationBoundary");
        requireNonBlank(executionId, "executionId");
        requireNonBlank(observationId, "observationId");
        requireNonBlank(outcomeId, "outcomeId");
        requireNonBlank(sourceReference, "sourceReference");
        requireNonBlank(expectedVsActualStatement, "expectedVsActualStatement");
        Objects.requireNonNull(observedAt, "observedAt");

        if (!InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT.equals(observationBoundary.contractId())) {
            throw new IllegalArgumentException("observation boundary contract required");
        }
        if (!observationBoundary.succeeded()) {
            throw new IllegalStateException("observed outcome cannot be created from unsuccessful observation boundary");
        }

        LearningEvidence evidence = new LearningEvidence(
                "learning-evidence-" + UUID.randomUUID(),
                executionId,
                observationId,
                outcomeId,
                sourceReference,
                observedAt,
                LearningEvidence.StatementNature.OBSERVED);
        Experience experience = learning.createExperience(
                executionId,
                evidence,
                expectedVsActualStatement,
                observedAt);

        IntelligenceCase linked = references.linkObservation(
                intelligenceCase,
                observationBoundary,
                "observation:" + observationId,
                List.of(sourceReference));
        linked = linked.withExternalReferences(
                List.of("execution:" + executionId, "outcome:" + outcomeId, "experience:" + experience.experienceId()),
                List.of("learning-evidence:" + evidence.evidenceId()),
                IntelligenceCaseStatus.REASSESSMENT);

        return new FeedbackResult(linked, evidence, experience);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public record FeedbackResult(
            IntelligenceCase intelligenceCase,
            LearningEvidence learningEvidence,
            Experience experience) {
        public FeedbackResult {
            Objects.requireNonNull(intelligenceCase, "intelligenceCase");
            Objects.requireNonNull(learningEvidence, "learningEvidence");
            Objects.requireNonNull(experience, "experience");
        }
    }
}
