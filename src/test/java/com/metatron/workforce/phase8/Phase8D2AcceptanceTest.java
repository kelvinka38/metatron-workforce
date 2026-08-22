package com.metatron.workforce.phase8;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase8D2AcceptanceTest {

    @Test
    void higherIsBetterImprovementUsesBaselineDelta() {
        PerformanceBaseline baseline =
                new PerformanceBaseline(
                        "base-1",
                        "units_per_hour",
                        10,
                        "HIGHER_IS_BETTER",
                        "evidence-1");

        ImprovementMeasurement result =
                ImprovementMeasurement.measure(baseline, 14);

        assertEquals(4, result.improvement());
        assertTrue(result.improved());
    }

    @Test
    void lowerIsBetterImprovementUsesInverseDelta() {
        PerformanceBaseline baseline =
                new PerformanceBaseline(
                        "base-2",
                        "minutes_per_unit",
                        20,
                        "LOWER_IS_BETTER",
                        "evidence-2");

        ImprovementMeasurement result =
                ImprovementMeasurement.measure(baseline, 15);

        assertEquals(5, result.improvement());
        assertTrue(result.improved());
    }

    @Test
    void regressionDoesNotBecomeImprovement() {
        PerformanceBaseline baseline =
                new PerformanceBaseline(
                        "base-3",
                        "quality_score",
                        90,
                        "HIGHER_IS_BETTER",
                        "evidence-3");

        ImprovementMeasurement result =
                ImprovementMeasurement.measure(baseline, 80);

        assertEquals(-10, result.improvement());
        assertFalse(result.improved());
    }

    @Test
    void capabilityCandidateCannotBypassValidation() {
        Phase8ImprovementService service =
                new Phase8ImprovementService();

        CapabilityCandidate candidate =
                service.proposeCapability(
                        "cap-1",
                        "worker-a",
                        "improved workflow",
                        "evidence-a");

        assertFalse(candidate.validated());

        CapabilityCandidate qualified =
                service.qualifyCapability(
                        candidate,
                        "validation-a");

        assertTrue(qualified.validated());
    }

    @Test
    void workforcePracticeRequiresMultipleWorkers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkforcePracticeCandidate(
                        "practice-1",
                        List.of("worker-a"),
                        "pattern",
                        null,
                        false));
    }

    @Test
    void workforcePracticeRequiresExplicitValidationBeforeValidatedState() {
        Phase8ImprovementService service =
                new Phase8ImprovementService();

        WorkforcePracticeCandidate candidate =
                service.proposeWorkforcePractice(
                        "practice-1",
                        List.of("worker-a:evidence", "worker-b:evidence"),
                        "same improved scheduling behavior");

        assertFalse(candidate.validated());

        WorkforcePracticeCandidate validated =
                service.validateWorkforcePractice(
                        candidate,
                        "controlled-validation");

        assertTrue(validated.validated());
        assertEquals(
                List.of("worker-a:evidence", "worker-b:evidence"),
                validated.workerEvidence());
    }

    @Test
    void invalidMetricDirectionIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PerformanceBaseline(
                        "base-invalid",
                        "metric",
                        10,
                        "AUTHORITY_DECISION",
                        "evidence"));
    }

    @Test
    void baselineRequiresEvidenceReference() {
        assertThrows(
                NullPointerException.class,
                () -> new PerformanceBaseline(
                        "base-invalid",
                        "metric",
                        10,
                        "HIGHER_IS_BETTER",
                        null));
    }

    @Test
    void improvementDoesNotCreateAuthorization() {
        PerformanceBaseline baseline =
                new PerformanceBaseline(
                        "base-auth",
                        "throughput",
                        10,
                        "HIGHER_IS_BETTER",
                        "evidence");

        ImprovementMeasurement result =
                ImprovementMeasurement.measure(baseline, 20);

        assertTrue(result.improved());

        // Deliberately no authorization state exists in D2.
        // Improvement is evidence of performance change, not authority.
        assertEquals("throughput", result.metric());
    }
}
