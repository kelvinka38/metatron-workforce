package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    /**
     * Second, deeper instance of the same bug class (production incident case-1a86decc, 2026-09-22): fixing
     * GeneralWorkspaceObservationVerifier's own workspace resolution was not sufficient, because for any
     * git-inspection or build/test criterion it delegates the actual command to
     * WorkerExecutionSandboxService.run(workerId, objectiveId, ...), which independently re-derived the
     * workspace via ObjectiveWorkspaceService.provision(objectiveId, workerId) -- the very same unfixed call
     * -- so the sandbox executed "git rev-parse HEAD" etc. against the empty legacy directory even though the
     * verifier had already correctly resolved the real attempt-scoped workspace for its file listing. This
     * test forces the git-inspection path (a real .git marker in the workspace) and uses a stub sandbox HTTP
     * server that asserts the incoming request's workspaceKey is the real attempt-scoped key, failing loudly
     * with a distinguishable error otherwise.
     */
    @Test
    void independentGitInspectionRunsAgainstTheRealAttemptScopedWorkspaceNotTheEmptyLegacyDirectory() throws Exception {
        Instant t = Instant.parse("2026-09-22T11:00:00Z");
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        ExecutionWorkspaceManager executionWorkspaces = new ExecutionWorkspaceManager(
                temp.resolve("executions"), attempts, new InMemoryExecutionWorkspaceBindingStore());
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(
                temp.resolve("legacy-objective-workspaces"), executionWorkspaces);

        ExecutionAttempt attempt = attempts.begin("dispatch-deliver-git", OBJECTIVE, STEP, WORKER,
                "assignment:deliver-git", "authorization:deliver-git", "runtime:deliver-git", 1, Duration.ofHours(1), t);
        ExecutionWorkspaceBinding binding = executionWorkspaces.allocate(attempt.attemptId(), attempt.fencingToken(), t.plusSeconds(1));
        Path primary = Path.of(binding.rootPath()).resolve("repos").resolve("primary");
        Files.createDirectories(primary.resolve(".git"));
        Files.writeString(primary.resolve("app.txt"), "delivered work\n");
        attempts.succeed(attempt.attemptId(), attempt.fencingToken(), t.plusSeconds(2));

        String expectedWorkspaceKey = Path.of(binding.rootPath()).getFileName().toString();

        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", exchange -> {
            JsonNode requestBody = json.readTree(exchange.getRequestBody());
            String workspaceKey = requestBody.path("workspaceKey").asText();
            List<String> args = json.convertValue(requestBody.path("args"),
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
            String output;
            boolean success;
            if (!expectedWorkspaceKey.equals(workspaceKey)) {
                success = false;
                output = "fatal: not a git repository (or any of the parent directories): .git -- wrong workspaceKey="
                        + workspaceKey + " expected=" + expectedWorkspaceKey;
            } else if (args.equals(List.of("rev-parse", "HEAD"))) {
                success = true; output = "1111111111111111111111111111111111111111\n";
            } else if (args.equals(List.of("rev-list", "--count", "HEAD"))) {
                success = true; output = "2\n";
            } else if (args.equals(List.of("diff", "--name-only", "HEAD^", "HEAD", "--"))) {
                success = true; output = "app.txt\n";
            } else if (args.equals(List.of("status", "--short"))) {
                success = true; output = "";
            } else {
                success = false; output = "unexpected command: " + args;
            }
            byte[] response = json.writeValueAsBytes(Map.of(
                    "success", success, "exitCode", success ? 0 : 1, "timedOut", false, "outputTruncated", false,
                    "output", output, "workspaceKey", workspaceKey,
                    "executable", requestBody.path("executable").asText(), "durationMillis", 5));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
            profiles.bind(WORKER, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                    GeneralWorkspaceAutonomousCapability.CAPABILITY, Instant.now());
            WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                    HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-token", profiles, workspaces, json);
            GeneralWorkspaceObservationVerifier verifier = new GeneralWorkspaceObservationVerifier(workspaces, sandbox);

            ObservationRequirement requirement = new ObservationRequirement(
                    OBJECTIVE + ":observation:" + STEP + ":criterion:1", OBJECTIVE, STEP, STEP + ":criterion:1",
                    "kelvinka38/metatron-workforce",
                    "produced workspace changes are committed in local Git; local Git HEAD/status is verified after commit",
                    List.of("general-action-composition:" + GeneralWorkspaceAutonomousCapability.CAPABILITY,
                            "requested-capability:" + GeneralWorkspaceAutonomousCapability.CAPABILITY),
                    Instant.now());

            ObservationReport report = verifier.observe(requirement, List.of(), t.plusSeconds(30)).orElseThrow();

            assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult(),
                    "independent Git inspection must run against the real attempt-scoped workspace, not the "
                            + "empty legacy directory -- observedState was: " + report.observedState());
        } finally {
            server.stop(0);
        }
    }
}
