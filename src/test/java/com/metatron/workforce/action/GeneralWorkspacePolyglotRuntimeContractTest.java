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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void cognitionSuppliedAbsoluteWorkingDirectoryFallsBackToTheRealDeterministicProjectDirectory() throws Exception {
        // Production incident (2026-09-22): a cognitive VERIFY reasoning pass invented an absolute path for
        // "workingDirectory" (e.g. something like "/app/workspace/web") instead of the workspace-relative
        // value the contract requires. ObjectiveWorkspaceService correctly rejected it with
        // "SecurityException: absolute workspace path denied", and the bounded local-retry policy then
        // burned all 3 attempts retrying the exact same doomed absolute path before escalating -- a
        // deterministic contract failure that blind retry can never heal. VERIFY must instead deterministically
        // locate the real project directory (the one PREPARE actually wrote a manifest into) rather than
        // trusting cognition's invented path or failing closed.
        try (Harness harness = new Harness(temp.resolve("absolute-working-directory"))) {
            harness.workspaces.write(harness.workspace, "web/package.json",
                    "{\"scripts\":{\"build\":\"node build.js\",\"test\":\"node test.js\"}}");
            harness.workspaces.write(harness.workspace, "web/package-lock.json", "{}");

            harness.execute("workspace.dependencies.install", Map.of("workingDirectory", "/app/workspace/web"));

            assertEquals(1, harness.requests.size());
            assertCommand(harness.requests.get(0), "web", "npm", List.of("ci"));
        }
    }

    @Test
    void blankWorkingDirectoryForANestedProjectResolvesToTheRealDeterministicProjectDirectoryNotRoot() throws Exception {
        // Production incident (case-757e8972, 2026-09-22): the prior fix (#503) only overrode an
        // absolute/invalid cognition-supplied workingDirectory; a BLANK input still returned the workspace
        // root unconditionally. When cognition (correctly, this time) supplied no workingDirectory at all
        // for a project that actually lives in a nested directory, dependenciesInstall/build/test inspected
        // only the workspace root, found nothing, and threw "workspace dependency system not detected" --
        // a second, distinct instance of "cognition is not authoritative for filesystem/project-root
        // identity" surfacing right after the absolute-path case was fixed.
        try (Harness harness = new Harness(temp.resolve("blank-nested"))) {
            harness.workspaces.write(harness.workspace, "web/package.json",
                    "{\"scripts\":{\"build\":\"node build.js\",\"test\":\"node test.js\"}}");
            harness.workspaces.write(harness.workspace, "web/package-lock.json", "{}");

            harness.execute("workspace.dependencies.install", Map.of());
            harness.execute("workspace.build.run", Map.of());
            harness.execute("workspace.test.run", Map.of());

            assertEquals(3, harness.requests.size());
            assertCommand(harness.requests.get(0), "web", "npm", List.of("ci"));
            assertCommand(harness.requests.get(1), "web", "npm", List.of("run", "build"));
            assertCommand(harness.requests.get(2), "web", "npm", List.of("run", "test"));
        }
    }

    @Test
    void blankWorkingDirectoryForARootProjectResolvesToTheWorkspaceRoot() throws Exception {
        try (Harness harness = new Harness(temp.resolve("blank-root"))) {
            harness.workspaces.write(harness.workspace, "package.json",
                    "{\"scripts\":{\"build\":\"node build.js\",\"test\":\"node test.js\"}}");
            harness.workspaces.write(harness.workspace, "package-lock.json", "{}");

            harness.execute("workspace.dependencies.install", Map.of());
            harness.execute("workspace.build.run", Map.of());
            harness.execute("workspace.test.run", Map.of());

            assertEquals(3, harness.requests.size());
            assertCommand(harness.requests.get(0), "", "npm", List.of("ci"));
            assertCommand(harness.requests.get(1), "", "npm", List.of("run", "build"));
            assertCommand(harness.requests.get(2), "", "npm", List.of("run", "test"));
        }
    }

    @Test
    void runtimeVerificationViaProcessRunWithBlankWorkingDirectoryUsesTheSameResolvedProjectDirectory() throws Exception {
        // "Runtime verification" has no dedicated action; it runs through workspace.process.run /
        // workspace.shell.run. Those must resolve the same project directory as dependencies/build/test
        // rather than defaulting to the workspace root unconditionally (their prior, unvalidated behavior).
        try (Harness harness = new Harness(temp.resolve("runtime-nested"))) {
            harness.workspaces.write(harness.workspace, "web/package.json",
                    "{\"scripts\":{\"start\":\"node server.js\"}}");
            harness.workspaces.write(harness.workspace, "web/server.js", "console.log('listening');\n");

            ActionFabric.ActionObservation observation = harness.invokeDirect(
                    "workspace.process.run", Map.of("executable", "node", "argsJson", "[\"server.js\"]"));

            assertTrue(observation.success());
            assertEquals(1, harness.requests.size());
            assertCommand(harness.requests.get(0), "web", "node", List.of("server.js"));
        }
    }

    @Test
    void unresolvedDependencySystemCarriesTheActualWorkspaceContentsInTheFailureMessage() throws Exception {
        // Diagnostic-instrumentation regression (2026-09-23): a real production Objective (case-6419a010)
        // hit "workspace dependency system not detected" on a resumed VERIFY dispatch with no way to tell,
        // from the Human-visible BLOCKER text alone, what the workspace actually contained at that moment.
        // The exception message now carries the resolved working directory and a bounded listing of the
        // workspace's real contents, which CognitiveWorkerRuntime's repeated-failure circuit breaker
        // already surfaces verbatim as "Last failure detail" -- so the next real occurrence is diagnosable
        // without another round of container-log archaeology.
        try (Harness harness = new Harness(temp.resolve("unresolved-dependency-system"))) {
            harness.workspaces.write(harness.workspace, "notes.txt", "no manifest here");

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> harness.invokeDirect("workspace.dependencies.install", Map.of()));

            assertTrue(failure.getMessage().contains("workspace dependency system not detected"));
            assertTrue(failure.getMessage().contains("workingDirectory=<workspace-root>"), failure.getMessage());
            assertTrue(failure.getMessage().contains("workspaceEntries=") && failure.getMessage().contains("notes.txt"),
                    failure.getMessage());
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
