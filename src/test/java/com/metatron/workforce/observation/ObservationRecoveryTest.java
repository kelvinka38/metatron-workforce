package com.metatron.workforce.observation;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationRecoveryTest {
    @TempDir Path temp;

    @Test
    void insufficientEvidenceRetriesAndRecoversWithoutHumanTransition() {
        AtomicInteger calls = new AtomicInteger();
        ObservationVerifier verifier = new ObservationVerifier() {
            @Override public boolean supports(ObservationRequirement requirement) { return true; }
            @Override public Optional<ObservationReport> observe(ObservationRequirement requirement,
                    List<String> executionEvidenceReferences, Instant at) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return Optional.of(new ObservationReport(
                            "report-inconclusive", requirement.requirementId(), requirement.objectiveId(),
                            requirement.target(), "first observation lacks independent evidence",
                            "p10-transient-observer", at, at, List.of(), 0.2,
                            ObservationReport.Quality.INSUFFICIENT, "evidence unavailable",
                            ObservationReport.CriterionResult.INCONCLUSIVE));
                }
                return Optional.of(new ObservationReport(
                        "report-pass", requirement.requirementId(), requirement.objectiveId(),
                        requirement.target(), "independent evidence now confirms criterion",
                        "p10-transient-observer", at, at,
                        List.of("evidence:independent-recovery"), 0.99,
                        ObservationReport.Quality.HIGH, "",
                        ObservationReport.CriterionResult.PASS));
            }
        };

        ObservationClosureService service = new ObservationClosureService(
                new InMemoryObservationStateStore(), List.of(verifier));
        Instant first = Instant.parse("2026-08-31T11:00:00Z");
        ObservationRequirement requirement = service.ensureRequirements(
                "objective-recovery", List.of(step()), first).getFirst();

        service.observeAvailable("objective-recovery", List.of("execution:evidence"), first.plusSeconds(1));
        assertEquals(ObservationClosureService.Verdict.PENDING, service.verdict("objective-recovery"));
        assertEquals(1, service.attempts(requirement.requirementId()));

        service.observeAvailable("objective-recovery", List.of("execution:evidence"), first.plusSeconds(2));
        assertEquals(ObservationClosureService.Verdict.PASSED, service.verdict("objective-recovery"));
        assertEquals(2, service.attempts(requirement.requirementId()));
        assertEquals("report-pass", service.reports("objective-recovery").getFirst().reportId());
    }

    @Test
    void absentVerifierExhaustsBoundedRecoveryDurably() {
        Path state = temp.resolve("observation-recovery.json");
        Instant first = Instant.parse("2026-08-31T11:10:00Z");
        ObservationClosureService service = new ObservationClosureService(
                new FileObservationStateStore(state), List.of());
        ObservationRequirement requirement = service.ensureRequirements(
                "objective-bounded", List.of(step()), first).getFirst();

        for (int i = 1; i <= ObservationClosureService.MAX_AUTONOMOUS_OBSERVATION_ATTEMPTS; i++) {
            service.observeAvailable("objective-bounded", List.of("execution:evidence"), first.plusSeconds(i));
        }

        assertEquals(ObservationClosureService.Verdict.INCONCLUSIVE, service.verdict("objective-bounded"));
        assertEquals(ObservationClosureService.MAX_AUTONOMOUS_OBSERVATION_ATTEMPTS,
                service.attempts(requirement.requirementId()));

        ObservationClosureService replacement = new ObservationClosureService(
                new FileObservationStateStore(state), List.of());
        assertEquals(ObservationClosureService.Verdict.INCONCLUSIVE, replacement.verdict("objective-bounded"));
        assertEquals(ObservationClosureService.MAX_AUTONOMOUS_OBSERVATION_ATTEMPTS,
                replacement.attempts(requirement.requirementId()));
    }

    private static ExecutionWorkSpec step() {
        return new ExecutionWorkSpec(
                "step-1", "Audit repository", "kelvinka38/metatron-workforce", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("repository audit is complete and evidence-backed"),
                List.of("independent repository state and cited findings"));
    }
}
