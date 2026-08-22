package com.metatron.workforce.phase8;

import java.time.Instant;
import java.util.Objects;

public final class LearningService {

    public Experience createExperience(
            String executionId,
            LearningEvidence evidence,
            String statement,
            Instant createdAt) {

        Objects.requireNonNull(evidence, "evidence");

        if (!evidence.executionId().equals(executionId))
            throw new IllegalArgumentException("evidence must belong to execution");

        return new Experience(
                "experience-" + evidence.evidenceId(),
                executionId,
                evidence.evidenceId(),
                statement,
                createdAt);
    }

    public ImprovementCandidate proposeImprovement(
            Evaluation evaluation,
            String candidateId,
            String proposedChange,
            Instant proposedAt) {

        Objects.requireNonNull(evaluation, "evaluation");

        return new ImprovementCandidate(
                candidateId,
                evaluation.evaluationId(),
                proposedChange,
                ImprovementCandidate.Status.PROPOSED,
                proposedAt);
    }

    public Adoption adopt(
            ImprovementCandidate candidate,
            Validation validation,
            String adoptionId,
            String decisionMakerId,
            Instant adoptedAt) {

        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(validation, "validation");

        if (!candidate.candidateId().equals(validation.candidateId()))
            throw new IllegalArgumentException("validation must belong to candidate");

        if (!validation.passed())
            throw new IllegalArgumentException("failed validation cannot be adopted");

        return new Adoption(
                adoptionId,
                candidate.candidateId(),
                validation.validationId(),
                decisionMakerId,
                adoptedAt);
    }

    public FutureBehavior createFutureBehavior(
            Adoption adoption,
            String behaviorId,
            String behavior,
            Instant effectiveAt) {

        Objects.requireNonNull(adoption, "adoption");

        return new FutureBehavior(
                behaviorId,
                adoption.adoptionId(),
                behavior,
                effectiveAt);
    }
}
