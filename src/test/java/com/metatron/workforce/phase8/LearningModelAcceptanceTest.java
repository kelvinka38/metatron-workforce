package com.metatron.workforce.phase8;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LearningModelAcceptanceTest {

    private static final Instant START = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T09:00:00Z");

    @Test
    void completeLearningLineageRemainsAttributable() {
        LearningEvidence evidence = new LearningEvidence(
                "evidence-001",
                "execution-001",
                "observation-001",
                "outcome-001",
                "execution-evidence-001",
                START,
                LearningEvidence.StatementNature.OBSERVED);

        LearningService service = new LearningService();

        Experience experience = service.createExperience(
                "execution-001",
                evidence,
                "Observed execution completed with reduced handling time.",
                END);

        Reflection reflection = new Reflection(
                "reflection-001",
                experience.experienceId(),
                "The reduced handling time appears repeatable under the same operating condition.",
                END);

        Evaluation evaluation = new Evaluation(
                "evaluation-001",
                reflection.reflectionId(),
                "Candidate improvement is suitable for validation.",
                END);

        ImprovementCandidate candidate = service.proposeImprovement(
                evaluation,
                "candidate-001",
                "Adopt the revised handling sequence.",
                END);

        Validation validation = new Validation(
                "validation-001",
                candidate.candidateId(),
                evidence.evidenceId(),
                true,
                "validator-001",
                END);

        Adoption adoption = service.adopt(
                candidate,
                validation,
                "adoption-001",
                "decision-maker-001",
                END);

        FutureBehavior behavior = service.createFutureBehavior(
                adoption,
                "behavior-001",
                "Use the validated handling sequence for future eligible work.",
                END);

        assertEquals("execution-001", experience.executionId());
        assertEquals(evidence.evidenceId(), experience.evidenceId());
        assertEquals(experience.experienceId(), reflection.experienceId());
        assertEquals(reflection.reflectionId(), evaluation.reflectionId());
        assertEquals(evaluation.evaluationId(), candidate.evaluationId());
        assertEquals(candidate.candidateId(), validation.candidateId());
        assertEquals(validation.validationId(), adoption.validationId());
        assertEquals(adoption.adoptionId(), behavior.adoptionId());
    }

    @Test
    void inferenceCannotBeConvertedIntoObservedFact() {
        LearningEvidence evidence = new LearningEvidence(
                "evidence-002",
                "execution-002",
                "observation-002",
                "outcome-002",
                "source-002",
                START,
                LearningEvidence.StatementNature.INFERRED);

        assertEquals(
                LearningEvidence.StatementNature.INFERRED,
                evidence.nature());

        assertNotEquals(
                LearningEvidence.StatementNature.OBSERVED,
                evidence.nature());
    }

    @Test
    void failedValidationCannotBeAdopted() {
        LearningService service = new LearningService();

        ImprovementCandidate candidate = new ImprovementCandidate(
                "candidate-003",
                "evaluation-003",
                "Change future behavior.",
                ImprovementCandidate.Status.PROPOSED,
                START);

        Validation validation = new Validation(
                "validation-003",
                candidate.candidateId(),
                "evidence-003",
                false,
                "validator-003",
                END);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.adopt(
                        candidate,
                        validation,
                        "adoption-003",
                        "decision-maker-003",
                        END));
    }

    @Test
    void candidateCannotDirectlyBecomeAdopted() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ImprovementCandidate(
                        "candidate-004",
                        "evaluation-004",
                        "Unauthorized direct adoption.",
                        ImprovementCandidate.Status.ADOPTED,
                        END));
    }

    @Test
    void learningCannotManufactureExecutionAuthority() {
        LearningService service = new LearningService();

        ImprovementCandidate candidate = new ImprovementCandidate(
                "candidate-005",
                "evaluation-005",
                "Change future behavior.",
                ImprovementCandidate.Status.PROPOSED,
                START);

        Validation validation = new Validation(
                "validation-005",
                candidate.candidateId(),
                "evidence-005",
                true,
                "validator-005",
                END);

        Adoption adoption = service.adopt(
                candidate,
                validation,
                "adoption-005",
                "decision-maker-005",
                END);

        FutureBehavior behavior = service.createFutureBehavior(
                adoption,
                "behavior-005",
                "Use validated behavior in future eligible work.",
                END);

        assertNotNull(behavior);
        assertNotNull(behavior.adoptionId());
        assertFalse(behavior.behavior().toLowerCase().contains("authorize"));
    }
}
