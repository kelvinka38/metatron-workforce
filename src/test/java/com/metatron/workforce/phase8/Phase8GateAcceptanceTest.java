package com.metatron.workforce.phase8;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class Phase8GateAcceptanceTest {

    private static final Instant T1 = Instant.parse("2026-01-01T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T09:00:00Z");

    @Test
    void completeLearningLineageIsReconstructable() {
        LearningEvidence evidence = new LearningEvidence(
                "g8-evidence-001", "execution-001", "observation-001", "outcome-001",
                "execution-evidence-001", T1, LearningEvidence.StatementNature.OBSERVED);
        LearningService service = new LearningService();
        Experience experience = service.createExperience("execution-001", evidence, "Observed outcome.", T2);
        Reflection reflection = new Reflection("reflection-001", experience.experienceId(), "Reflection.", T2);
        Evaluation evaluation = new Evaluation("evaluation-001", reflection.reflectionId(), "Evaluation.", T2);
        ImprovementCandidate candidate = service.proposeImprovement(evaluation, "candidate-001", "Change behavior.", T2);
        Validation validation = new Validation("validation-001", candidate.candidateId(), evidence.evidenceId(), true, "validator-001", T2);
        Adoption adoption = service.adopt(candidate, validation, "adoption-001", "decision-maker-001", T2);
        FutureBehavior behavior = service.createFutureBehavior(adoption, "behavior-001", "Use validated behavior.", T2);

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
    void inferenceAndEstimateRemainDistinctFromObservedFact() {
        assertEquals(LearningEvidence.StatementNature.INFERRED,
                new LearningEvidence("i", "e", "o", "out", "src", T1,
                        LearningEvidence.StatementNature.INFERRED).nature());
        assertEquals(LearningEvidence.StatementNature.ESTIMATED,
                new LearningEvidence("x", "e", "o", "out", "src", T1,
                        LearningEvidence.StatementNature.ESTIMATED).nature());
    }

    @Test
    void failedValidationCannotBeAdopted() {
        LearningService service = new LearningService();
        ImprovementCandidate candidate = new ImprovementCandidate(
                "candidate-002", "evaluation-002", "Change behavior.",
                ImprovementCandidate.Status.PROPOSED, T1);
        Validation validation = new Validation(
                "validation-002", candidate.candidateId(), "evidence-002", false, "validator-002", T2);

        assertThrows(IllegalArgumentException.class,
                () -> service.adopt(candidate, validation, "adoption-002", "decision-maker-002", T2));
    }

    @Test
    void historicalEvidenceSurvivesLaterInterpretation() {
        LearningEvidence original = new LearningEvidence(
                "historical-001", "execution-003", "observation-003", "outcome-003",
                "source-003", T1, LearningEvidence.StatementNature.OBSERVED);
        Experience firstInterpretation = new Experience(
                "experience-original", original.executionId(), original.evidenceId(),
                "Original interpretation.", T1);
        Experience laterInterpretation = new Experience(
                "experience-later", original.executionId(), original.evidenceId(),
                "Later interpretation.", T2);

        assertEquals("Original interpretation.", firstInterpretation.statement());
        assertEquals("Later interpretation.", laterInterpretation.statement());
        assertEquals(LearningEvidence.StatementNature.OBSERVED, original.nature());
        assertEquals(T1, original.observedAt());
        assertEquals("historical-001", original.evidenceId());
    }

    @Test
    void futureBehaviorRequiresAdoptionAndCannotCreateAuthority() {
        LearningService service = new LearningService();
        ImprovementCandidate candidate = new ImprovementCandidate(
                "candidate-004", "evaluation-004", "Validated change.",
                ImprovementCandidate.Status.PROPOSED, T1);
        Validation validation = new Validation(
                "validation-004", candidate.candidateId(), "evidence-004", true, "validator-004", T2);
        Adoption adoption = service.adopt(candidate, validation, "adoption-004", "decision-maker-004", T2);
        FutureBehavior behavior = service.createFutureBehavior(
                adoption, "behavior-004", "Use validated behavior for eligible future work.", T2);

        assertEquals(adoption.adoptionId(), behavior.adoptionId());
        assertFalse(behavior.behavior().toLowerCase().contains("authorize"));
        assertFalse(behavior.behavior().toLowerCase().contains("grant authority"));
    }

    @Test
    void candidateCannotSkipValidationOrBecomeAdoptedDirectly() {
        assertThrows(IllegalArgumentException.class,
                () -> new ImprovementCandidate(
                        "candidate-005", "evaluation-005", "Skip validation.",
                        ImprovementCandidate.Status.ADOPTED, T2));
    }
}
