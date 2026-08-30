package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase8.LearningEvidence;
import com.metatron.workforce.phase8.LearningService;
import com.metatron.workforce.phase9.BoundaryProvenance;
import com.metatron.workforce.phase9.BoundaryResult;
import com.metatron.workforce.phase9.BoundaryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class IntelligenceOutcomeLearningBridgeTest {
    @Test
    void observedOutcomeCreatesLearningEvidenceExperienceAndCaseReferences() {
        IntelligenceOutcomeLearningBridge bridge = new IntelligenceOutcomeLearningBridge(new LearningService());
        BoundaryResult observation = new BoundaryResult(
                "obs-boundary-1", InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT,
                BoundaryStatus.SUCCESS, "observation-output", "authority:observation",
                new BoundaryProvenance("observation", "evidence:obs-1", Instant.parse("2026-08-30T06:00:00Z")));

        var result = bridge.recordObservedOutcome(
                baseCase(), observation, "execution-1", "observation-1", "outcome-1",
                "evidence:observed-outcome-1", "expected margin 20%, observed margin 16%",
                Instant.parse("2026-08-30T06:00:00Z"));

        assertEquals(LearningEvidence.StatementNature.OBSERVED, result.learningEvidence().nature());
        assertEquals("execution-1", result.experience().executionId());
        assertTrue(result.intelligenceCase().externalInstitutionalReferences().contains("execution:execution-1"));
        assertTrue(result.intelligenceCase().externalInstitutionalReferences().contains("outcome:outcome-1"));
        assertTrue(result.intelligenceCase().externalInstitutionalReferences().stream().anyMatch(ref -> ref.startsWith("experience:")));
        assertTrue(result.intelligenceCase().evidenceReferences().contains("evidence:observed-outcome-1"));
        assertEquals(IntelligenceCaseStatus.REASSESSMENT, result.intelligenceCase().status());
    }

    @Test
    void refusesToManufactureOutcomeFromUnavailableObservation() {
        IntelligenceOutcomeLearningBridge bridge = new IntelligenceOutcomeLearningBridge(new LearningService());
        BoundaryResult unavailable = new BoundaryResult(
                "obs-boundary-1", InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT,
                BoundaryStatus.UNAVAILABLE, null, "authority:observation",
                new BoundaryProvenance("observation", "evidence:missing", Instant.parse("2026-08-30T06:00:00Z")));

        assertThrows(IllegalStateException.class, () -> bridge.recordObservedOutcome(
                baseCase(), unavailable, "execution-1", "observation-1", "outcome-1",
                "evidence:missing", "unknown", Instant.parse("2026-08-30T06:00:00Z")));
    }

    private static IntelligenceCase baseCase() {
        Instant now = Instant.parse("2026-08-30T05:00:00Z");
        return new IntelligenceCase(
                "case-outcome", "conversation:1", "human:1", "improve margin",
                IntelligenceDepth.DEEP, IntelligenceCaseStatus.WAITING_ON_EXTERNAL_STATE,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recommendation", "", List.of(), now, now);
    }
}
