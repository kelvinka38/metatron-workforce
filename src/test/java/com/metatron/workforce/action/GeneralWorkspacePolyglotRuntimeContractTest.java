package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspacePolyglotRuntimeContractTest {
    private static final String WORKER = "WORKER-GENERAL-ENGINEERING";
    private static final String AUTH = "authorization:test:polyglot";
    private static final String OBJECTIVE = "objective:test:polyglot";

    @TempDir Path temp;

    @Test
    void reactSourceProjectPrepareCreatesDeterministicRunnableScaffold() throws Exception {
        try (Harness harness = new Harness(temp.resolve("prepare-react"))) {
            String original = "import React from 'react';\n"
                    + "export default function App(){ return <h1>Metatron Workforce Control Center</h1>; }\n";
            harness.workspaces.write(harness.workspace, "src/App.js", original);

            ActionFabric.ActionObservation observation =
                    harness.invokeDirect("workspace.project.prepare", Map.of());

            assertTrue(observation.success());
            assertEquals("node-react", observation.outputs().get("projectKind"));
            assertEquals("package.json", observation.outputs().get("manifestPath"));
            assertEquals(original, harness.workspaces.read(harness.workspace, "src/App.js"),
                    "PREPARE must not overwrite the requested work product");
            assertTrue(harness.workspaces.read(harness.workspace, "package.json").contains("\"vite build\""));
            assertTrue(harness.workspaces.read(harness.workspace, "package.json").contains("\"node --test test/*.test.js\""));
            assertTrue(harness.workspaces.read(harness.workspace, "src/main.jsx").contains("createRoot"));
            assertTrue(harness.workspaces.read(harness.workspace, "index.html").contains("src/main.jsx"));
            assertTrue(harness.workspaces.read(harness.workspace, "src/App.jsx").contains("Metatron Workforce Control Center"));
            assertTrue(observation.evidenceReferences().contains("workspace-project-kind:node-react"));
        }
    }

    @Test
    void nodeProjectUsesDetectedPackageManagerScriptsAndProjectWorkingDirectory() throws Exception {
        try (Harness harness = new Harness(temp.resolve("node"))) {
            harness.workspaces.write(harness.workspace, "web/package.json",
                    "{\"scripts\":{\"build\":\"node build.js\",\"test\":\"node test.js\"}}");
            harness.workspaces.write(harness.workspace, "web/package-lock.json", "{}");

            harness.execute("workspace.dependencies.install", Map.of("workingDirectory", "web"));
            harness.execute("workspace.build.run", Map.of("workingDirectory", "web"));
            harness.execute("workspace.test.run", Map.of("workingDirectory", "web"));

            assertEquals(3, harness.requests.size());
            assertCommand(harness.requests.get(0), "web", "npm", List.of("ci"));
            assertCommand(harness.requests.get(1), "web", "npm", List.of("run", "build"));
            assertCommand(harness.requests.get(2), "web", "npm", List.of("run", "test"));
        }
    }

    @Test
    void pythonProjectUsesPipCompileallAndPytestInsideProjectDirectory() throws Exception {
        try (Harness harness = new Harness(temp.resolve("python"))) {
            harness.workspaces.write(harness.workspace, "service/pyproject.toml",
                    "[build-system]\nrequires=[]\nbuild-backend=\"setuptools.build_meta\"\n");
            harness.workspaces.write(harness.workspace, "service/tests/test_ok.py",
                    "def test_ok():\n    assert True\n");

            harness.execute("workspace.dependencies.install", Map.of("workingDirectory", "service"));
            harness.execute("workspace.build.run", Map.of("workingDirectory", "service"));
            harness.execute("workspace.test.run", Map.of("workingDirectory", "service"));

            assertEquals(3, harness.requests.size());
            assertCommand(harness.requests.get(0), "service", "python3",
                    List.of("-m", "pip", "install", "--disable-pip-version-check", "-e", "."));
            assertCommand(harness.requests.get(1), "service", "python3",
                    List.of("-m", "compileall", "-q", "."));
            assertCommand(harness.requests.get(2), "service", "python3",
                    List.of("-m", "pytest", "-q"));
        }
    }

    private static void assertCommand(JsonNode request, String cwd, String executable, List<String> args) {
        ObjectMapper json = new ObjectMapper();
        assertEquals(cwd, request.path("workingDirectory").asText());
        assertEquals(executable, request.path("executable").asText());
        assertEquals(args, json.convertValue(request.path("args"),
                json.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    private static final class Harness implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper();
        private final HttpServer server;
        private final List<JsonNode> requests = new ArrayList<>();
        private final ObjectiveWorkspaceService workspaces;
        private final ObjectiveWorkspaceService.ObjectiveWorkspace workspace;
        private final ActionFabric fabric;
        private final List<ActionFabric.Action> catalogActions;

        private Harness(Path root) throws Exception {
            workspaces = new ObjectiveWorkspaceService(root.resolve("workspaces"));
            workspace = workspaces.provision(OBJECTIVE, WORKER);
            WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
            profiles.bind(WORKER, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                    "execution.general.workspace", Instant.now());

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/run", exchange -> {
                JsonNode request = json.readTree(exchange.getRequestBody());
                requests.add(request);
                byte[] response = json.writeValueAsBytes(Map.of(
                        "success", true,
                        "exitCode", 0,
                        "timedOut", false,
                        "outputTruncated", false,
                        "output", "ok",
                        "workspaceKey", request.path("workspaceKey").asText(),
                        "executable", request.path("executable").asText(),
                        "durationMillis", 5));
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();

            HttpClient http = HttpClient.newHttpClient();
            WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                    http, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "polyglot-token", profiles, workspaces, json);
            RepositoryWorkspaceMaterializationService repositories =
                    new RepositoryWorkspaceMaterializationService(http, "", workspaces, json);
            GitHubWorkspaceProposalPublisher proposals =
                    new GitHubWorkspaceProposalPublisher(http, "", workspaces, sandbox, json);
            GeneralWorkspaceActionCatalog catalog =
                    new GeneralWorkspaceActionCatalog(workspaces, sandbox, profiles, repositories, proposals, json);
            catalogActions = catalog.actions(WORKER, AUTH, OBJECTIVE);
            fabric = new ActionFabric(catalogActions);
        }

        private ActionFabric.ActionObservation invokeDirect(String actionRef, Map<String, String> inputs) {
            ActionFabric.Action action = catalogActions.stream()
                    .filter(candidate -> actionRef.equals(candidate.actionRef()))
                    .findFirst().orElseThrow();
            return action.invoke(new ActionFabric.ActionRequest(
                    actionRef, WORKER, "assignment:test", AUTH, OBJECTIVE,
                    "step:test", "idempotency:prepare", true, inputs));
        }

        private void execute(String actionRef, Map<String, String> inputs) {
            ActionFabric.ActionObservation observation = fabric.execute(new ActionFabric.ActionRequest(
                    actionRef, WORKER, "assignment:test", AUTH, OBJECTIVE,
                    "step:test", "idempotency:test", false, inputs));
            assertTrue(observation.success());
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
