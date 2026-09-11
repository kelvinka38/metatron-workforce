package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.observation.FounderWorkerWorkProductObservationVerifier;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.observation.ObservationRequirement;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FounderDefinedWorkerAutonomousExecutionAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void formedComposerReceivesRealAssignmentExecutionAttemptAndObservedDurableProduct(@TempDir Path temp) {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
        RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(new RuntimeRegistry());
        FounderDefinedWorkerFormationService formation = new FounderDefinedWorkerFormationService(
                core, profiles, constitution, runtimes);

        formation.form("composer/artist",
                "Create for me a worker, role composer/artist. Produce original musical work.", NOW);
        formation.form("copywriter",
                "Create for me a worker, role copywriter. Produce original written copy.", NOW);

        WorkerIntelligenceService intelligence = request -> {
            assertEquals("WORKER-COMPOSER-ARTIST", request.requester(),
                    "target-aware allocation must not hand this work to another Founder-defined Worker");
            assertEquals(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, request.capability());
            assertTrue(request.context().contains("CANONICAL WORKER CONSTITUTION"));
            return new WorkerIntelligenceService.Response(
                    "worker-cognition:composer-acceptance",
                    "Original work product: syncopated pop concept with a call-and-response hook.",
                    List.of("worker-cognition:composer-acceptance"));
        };

        FounderWorkerWorkProductStore products = new FounderWorkerWorkProductStore(
                temp.resolve("products"), new ObjectMapper());
        FounderDefinedCognitiveWorkCapability delegate = new FounderDefinedCognitiveWorkCapability(
                core, profiles, constitution, intelligence, products);
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK,
                null, attempts, runtimes);

        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "founder-worker-cognitive-work",
                "WORKER-COMPOSER-ARTIST create an original pop song concept with a strong rhythmic hook",
                "WORKER-COMPOSER-ARTIST",
                FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("canonical Worker WORKER-COMPOSER-ARTIST produces the requested cognitive work product"),
                List.of("founder-worker-work-product durable cognitive work product"));

        AutonomousExecutionCapability.CapabilityResult result = governed.execute(
                new AutonomousExecutionCapability.CapabilityRequest(
                        "human-primary", "metatron", "objective:composer:acceptance", work));

        assertTrue(result.success());
        assertEquals("WORKER-COMPOSER-ARTIST", result.workerId());
        assertTrue(result.evidenceReferences().stream().anyMatch(value -> value.startsWith("execution-attempt:")));
        assertTrue(result.evidenceReferences().stream().anyMatch(value -> value.startsWith("founder-worker-work-product:")));
        assertEquals(1, attempts.all().size());
        assertEquals("WORKER-COMPOSER-ARTIST", attempts.all().getFirst().workerId());
        assertEquals(1, core.allAssignments().size());
        assertEquals("WORKER-COMPOSER-ARTIST", core.allAssignments().getFirst().workerId());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, core.allAssignments().getFirst().status());

        FounderWorkerWorkProductObservationVerifier verifier =
                new FounderWorkerWorkProductObservationVerifier(products);
        ObservationRequirement requirement = new ObservationRequirement(
                "requirement:composer-product",
                "objective:composer:acceptance",
                work.stepId(),
                work.stepId() + ":criterion:1",
                "WORKER-COMPOSER-ARTIST",
                work.acceptanceCriteria().getFirst(),
                work.evidenceRequirements(),
                NOW);
        ObservationReport report = verifier.observe(requirement, result.evidenceReferences(), NOW)
                .orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(ObservationReport.Quality.HIGH, report.quality());
        assertTrue(report.evidenceReferences().stream().anyMatch(value -> value.startsWith("founder-worker-work-product:")));
    }
}
