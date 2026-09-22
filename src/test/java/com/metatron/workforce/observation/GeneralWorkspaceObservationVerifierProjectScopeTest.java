package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspaceObservationVerifierProjectScopeTest {
    private static final String OBJECTIVE = "objective:test:nested-project-observation";
    private static final String WORKER = GeneralWorkspaceAutonomousCapability.WORKER_ID;

    @TempDir Path temp;

    @Test
    void independentObservationRerunsTestsInNearestChangedPythonProject() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            requests.add(request);
            String executable = request.path("executable").asText();
            List<String> args = json.convertValue(request.path("args"),
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
            String output;
            if ("git".equals(executable) && args.equals(List.of("rev-list", "--count", "HEAD"))) {
                output = "2\n";
            } else if ("git".equals(executable)
                    && args.equals(List.of("diff-tree", "--no-commit-id", "--name-only", "-r", "--root", "HEAD"))) {
                output = "acceptance/general-engineering-go1/slugify.py\n";
            } else if ("python3".equals(executable)
                    && args.equals(List.of("-m", "pytest", "-q"))) {
                output = "2 passed\n";
            } else {
                output = "unexpected command: " + executable + " " + args;
            }
            boolean success = !output.startsWith("unexpected command");
            byte[] response = json.writeValueAsBytes(Map.of(
                    "success", success,
                    "exitCode", success ? 0 : 1,
                    "timedOut", false,
                    "outputTruncated", false,
                    "output", output,
                    "workspaceKey", request.path("workspaceKey").asText(),
                    "executable", executable,
                    "durationMillis", 5));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("workspaces"));
            var workspace = workspaces.provision(OBJECTIVE, WORKER);
            Files.createDirectories(workspace.path().resolve(".git"));
            workspaces.write(workspace, "acceptance/general-engineering-go1/pytest.ini", "[pytest]\ntestpaths = tests\n");
            workspaces.write(workspace, "acceptance/general-engineering-go1/slugify.py", "def slugify(value): return value\n");
            workspaces.write(workspace, "acceptance/general-engineering-go1/tests/test_slugify.py", "def test_ok(): assert True\n");

            WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
            profiles.bind(WORKER, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                    "execution.general.workspace", Instant.now());
            WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "observation-test-token", profiles, workspaces, json);
            GeneralWorkspaceObservationVerifier verifier =
                    new GeneralWorkspaceObservationVerifier(workspaces, sandbox);

            ObservationRequirement requirement = new ObservationRequirement(
                    "requirement:nested-test", OBJECTIVE, "step-1", "criterion-1",
                    "kelvinka38/metatron-workforce",
                    "Relevant fixture tests pass after the latest source change",
                    List.of("general-action-runtime:execution.general.workspace",
                            "requested-capability:execution.general.workspace"),
                    Instant.now());

            ObservationReport report = verifier.observe(requirement, List.of(), Instant.now()).orElseThrow();

            assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
            assertTrue(report.evidenceReferences().stream().anyMatch(value ->
                    value.contains("workingDirectory=acceptance/general-engineering-go1")));
            JsonNode verification = requests.stream()
                    .filter(request -> "python3".equals(request.path("executable").asText()))
                    .findFirst().orElseThrow();
            assertEquals("acceptance/general-engineering-go1",
                    verification.path("workingDirectory").asText());
        } finally {
            server.stop(0);
        }
    }
}
