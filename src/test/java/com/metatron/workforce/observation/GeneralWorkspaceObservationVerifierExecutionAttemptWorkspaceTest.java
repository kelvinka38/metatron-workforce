package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.runtime.execution.ExecutionWorkspaceBinding;
import com.metatron.workforce.runtime.execution.ExecutionWorkspaceManager;
import com.metatron.workforce.runtime.execution.InMemoryExecutionWorkspaceBindingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident (case-bb53389d, 2026-09-22): a completed Objective's DELIVER step ran inside the
 * canonical attempt-scoped ExecutionWorkspaceManager workspace (via ObjectiveWorkspaceService.provision()
 * with an ExecutionAttemptContext bound), but independent Observation runs on a later poll cycle with no
 * such context bound, so its own provision() call silently fell through to the legacy per-objective+worker
 * directory -- a directory the real attempt never wrote to -- and reported "Objective workspace exists but
 * contains no work product" for every objective whose work actually landed through the attempt-scoped path.
 */
class GeneralWorkspaceObservationVerifierExecutionAttemptWorkspaceTest {
    private static final String OBJECTIVE = "objective:test:attempt-scoped-observation";
    private static final String STEP = "deliver-1";
    private static final String WORKER = GeneralWorkspaceAutonomousCapability.WORKER_ID;

    @TempDir Path temp;

    @Test
    void independentObservationFindsTheRealAttemptScopedWorkProductNotTheEmptyLegacyDirectory() throws Exception {
        Instant t = Instant.parse("2026-09-22T11:00:00Z");
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        ExecutionWorkspaceManager executionWorkspaces = new ExecutionWorkspaceManager(
                temp.resolve("executions"), attempts, new InMemoryExecutionWorkspaceBindingStore());
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(
                temp.resolve("legacy-objective-workspaces"), executionWorkspaces);

        // Simulate the real DELIVER execution: an attempt is leased, its attempt-scoped workspace is
        // allocated, real work is written into it, and the attempt succeeds -- exactly what
        // GeneralWorkspaceAutonomousCapability does in production while ExecutionAttemptContext is bound.
        ExecutionAttempt attempt = attempts.begin("dispatch-deliver", OBJECTIVE, STEP, WORKER,
                "assignment:deliver", "authorization:deliver", "runtime:deliver", 1, Duration.ofHours(1), t);
        ExecutionWorkspaceBinding binding = executionWorkspaces.allocate(attempt.attemptId(), attempt.fencingToken(), t.plusSeconds(1));
        Path primary = Path.of(binding.rootPath()).resolve("repos").resolve("primary");
        Files.createDirectories(primary);
        Files.writeString(primary.resolve("README.md"), "# Delivered work product\n");
        attempts.succeed(attempt.attemptId(), attempt.fencingToken(), t.plusSeconds(2));

        // Independent Observation always runs later, with no ExecutionAttemptContext bound on this thread.
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        profiles.bind(WORKER, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                GeneralWorkspaceAutonomousCapability.CAPABILITY, Instant.now());
        WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1"), "unused-token",
                profiles, workspaces, new ObjectMapper());
        GeneralWorkspaceObservationVerifier verifier = new GeneralWorkspaceObservationVerifier(workspaces, sandbox);

        ObservationRequirement requirement = new ObservationRequirement(
                OBJECTIVE + ":observation:" + STEP + ":criterion:1", OBJECTIVE, STEP, STEP + ":criterion:1",
                "kelvinka38/metatron-workforce",
                "Delivered file exists in the Objective workspace",
                List.of("general-action-composition:" + GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        "requested-capability:" + GeneralWorkspaceAutonomousCapability.CAPABILITY),
                Instant.now());

        ObservationReport report = verifier.observe(requirement, List.of(), t.plusSeconds(30)).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult(),
                "independent Observation must find the real attempt-scoped work product, not the empty "
                        + "legacy directory -- observedState was: " + report.observedState());
        assertTrue(report.evidenceReferences().stream().anyMatch(ref -> ref.contains(binding.workspaceId())),
                "Observation evidence must reference the actual attempt-scoped workspace that DELIVER wrote to");
    }
}
