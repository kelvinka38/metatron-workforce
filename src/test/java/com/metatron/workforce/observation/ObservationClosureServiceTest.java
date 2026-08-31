package com.metatron.workforce.observation;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationClosureServiceTest {
    @TempDir Path temp;

    @Test
    void executionEvidenceAloneCannotPassObservation() {
        ObservationClosureService service = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of());
        Instant at = Instant.parse("2026-08-31T04:00:00Z");

        service.ensureRequirements("objective-a", List.of(verifiableStep()), at);
        service.observeAvailable("objective-a", List.of("execution:evidence:claimed-success"), at);

        assertEquals(ObservationClosureService.Verdict.PENDING, service.verdict("objective-a"));
        assertEquals(1, service.requirements("objective-a").size());
        assertTrue(service.reports("objective-a").isEmpty());
    }

    @Test
    void passRequiresIndependentReportWithEvidence() {
        ObservationClosureService service = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of());
        Instant at = Instant.parse("2026-08-31T04:00:00Z");
        ObservationRequirement requirement = service.ensureRequirements(
                "objective-a", List.of(verifiableStep()), at).getFirst();

        service.recordReport(pass(requirement, "report-1", at.plusSeconds(10)));

        assertEquals(ObservationClosureService.Verdict.PASSED, service.verdict("objective-a"));
        assertTrue(service.verifiedEvidenceReferences("objective-a").contains("observation-report:report-1"));
        assertTrue(service.verifiedEvidenceReferences("objective-a").contains("evidence:observed-repository-state"));
    }

    @Test
    void insufficientOrFailedObservationNeverClosesObjective() {
        ObservationClosureService service = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of());
        Instant at = Instant.parse("2026-08-31T04:00:00Z");
        ObservationRequirement requirement = service.ensureRequirements(
                "objective-a", List.of(verifiableStep()), at).getFirst();

        service.recordReport(new ObservationReport(
                "report-fail", requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                "repository state contradicts criterion", "independent-repository-read", at.plusSeconds(10),
                at.plusSeconds(10), List.of("evidence:contradiction"), 0.99,
                ObservationReport.Quality.HIGH, "criterion not satisfied",
                ObservationReport.CriterionResult.FAIL));

        assertEquals(ObservationClosureService.Verdict.FAILED, service.verdict("objective-a"));
        assertThrows(IllegalStateException.class, () -> service.verifiedEvidenceReferences("objective-a"));
    }

    @Test
    void staleObservationReportIsFencedAndDurableStateSurvivesServiceReplacement() {
        Path state = temp.resolve("observation-state.json");
        Instant at = Instant.parse("2026-08-31T04:00:00Z");
        ObservationClosureService first = new ObservationClosureService(
                new FileObservationStateStore(state), List.of());
        ObservationRequirement requirement = first.ensureRequirements(
                "objective-a", List.of(verifiableStep()), at).getFirst();
        first.recordReport(pass(requirement, "report-new", at.plusSeconds(20)));

        assertThrows(IllegalStateException.class, () -> first.recordReport(
                pass(requirement, "report-stale", at.plusSeconds(10))));

        ObservationClosureService replacement = new ObservationClosureService(
                new FileObservationStateStore(state), List.of());
        assertEquals(ObservationClosureService.Verdict.PASSED, replacement.verdict("objective-a"));
        assertEquals("report-new", replacement.reports("objective-a").getFirst().reportId());
    }

    @Test
    void planWithoutAcceptanceCriteriaFailsClosed() {
        ObservationClosureService service = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of());
        ExecutionWorkSpec legacy = new ExecutionWorkSpec(
                "step-legacy", "Audit repository", "repo", "repository.audit.read", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.ensureRequirements("objective-legacy", List.of(legacy), Instant.now()));

        assertEquals("observation-requirements-missing:step-legacy", failure.getMessage());
    }

    private static ExecutionWorkSpec verifiableStep() {
        return new ExecutionWorkSpec(
                "step-1", "Audit repository", "kelvinka38/metatron-workforce", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("repository audit is complete and evidence-backed"),
                List.of("independent repository state and cited findings"));
    }

    private static ObservationReport pass(ObservationRequirement requirement, String reportId, Instant at) {
        return new ObservationReport(
                reportId, requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                "repository state independently confirms audit criterion", "independent-repository-read",
                at, at, List.of("evidence:observed-repository-state"), 0.98,
                ObservationReport.Quality.HIGH, "", ObservationReport.CriterionResult.PASS);
    }
}
