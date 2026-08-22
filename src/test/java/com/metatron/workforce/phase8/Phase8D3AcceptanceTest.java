package com.metatron.workforce.phase8;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase8D3AcceptanceTest {

    @Test
    void repeatedSuccessfulBehaviorCanBecomeCapabilityCandidate() {

        CapabilityGrowthService service = new CapabilityGrowthService();

        CapabilityCandidate candidate =
                service.proposeCapability(
                        "cap-001",
                        "worker-001",
                        "successful planning behavior",
                        "evidence-worker-001");

        assertNotNull(candidate);
        assertFalse(candidate.isQualified());
    }

    @Test
    void capabilityCandidateCannotBypassValidation() {

        CapabilityGrowthService service = new CapabilityGrowthService();

        CapabilityCandidate candidate =
                service.proposeCapability(
                        "cap-002",
                        "worker-001",
                        "behavior",
                        "evidence");

        assertFalse(candidate.isQualified());

        CapabilityCandidate qualified =
                service.qualify(candidate, "validation-evidence");

        assertTrue(qualified.isQualified());
        assertEquals(
                "validation-evidence",
                qualified.validationEvidence());
    }

    @Test
    void workforcePracticeRequiresEvidenceFromMultipleWorkers() {

        WorkforceLearningService service =
                new WorkforceLearningService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.proposePattern(
                        "practice-invalid",
                        List.of("worker-a"),
                        "pattern"));
    }

    @Test
    void workforcePracticeCanBeValidatedOnlyAfterCrossWorkerEvidence() {

        WorkforceLearningService service =
                new WorkforceLearningService();

        WorkforcePracticeCandidate candidate =
                service.proposePattern(
                        "practice-001",
                        List.of(
                                "worker-a:evidence-1",
                                "worker-b:evidence-2"),
                        "same successful scheduling behavior");

        assertFalse(candidate.isQualified());

        WorkforcePracticeCandidate validated =
                service.validate(
                        candidate,
                        "cross-worker-validation-evidence");

        assertTrue(validated.isQualified());
        assertEquals(
                "cross-worker-validation-evidence",
                validated.validationEvidence());
    }

    @Test
    void learningDoesNotSilentlyBecomeKnowledgeOrPolicy() {

        ImprovementCandidate candidate =
                new ImprovementCandidate(
                        "improvement-001",
                        "new heuristic",
                        "evidence-001",
                        false);

        assertDoesNotThrow(
                () -> InstitutionalLearningBoundary.requireNotKnowledge(candidate));

        assertDoesNotThrow(
                () -> InstitutionalLearningBoundary.requireNotPolicy(candidate));
    }

    @Test
    void capabilityGrowthDoesNotExpandAuthority() {

        CapabilityGrowthService service =
                new CapabilityGrowthService();

        CapabilityCandidate candidate =
                service.proposeCapability(
                        "cap-003",
                        "worker-001",
                        "behavior",
                        "evidence");

        assertFalse(candidate.isQualified());

        assertDoesNotThrow(
                () -> service.qualify(
                        candidate,
                        "validation-evidence"));

        assertFalse(
                candidate.getClass()
                        .getDeclaredFields()
                        == null);
    }

    @Test
    void workforceLearningRemainsAttributableToWorkerEvidence() {

        WorkforceLearningService service =
                new WorkforceLearningService();

        WorkforcePracticeCandidate candidate =
                service.proposePattern(
                        "practice-002",
                        List.of(
                                "worker-a:evidence-a",
                                "worker-b:evidence-b",
                                "worker-c:evidence-c"),
                        "validated repeated pattern");

        assertEquals(3, candidate.workerEvidence().size());
        assertTrue(
                candidate.workerEvidence().stream()
                        .allMatch(e -> e.contains("worker-")));
    }
}
