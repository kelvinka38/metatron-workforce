package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.ExecutionAttemptContext;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.testing.GovernanceTestHarness;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Root-cause fix (2026-09-23, case build-and-deliver "Metatron Workforce Control Center"): DELIVER's
 * deterministic `git add -A` (GeneralCognitiveWorkerBrain.governedGitPrecondition(), the normal path for
 * a fresh application with no narrower governed target) staged and committed an entire installed
 * `node_modules/` tree in full -- nothing in produce/prepare/dependencies-install ever excluded it --
 * so GitHubWorkspaceProposalPublisher's MAX_CHANGED_PATHS=50 budget deterministically rejected the
 * resulting proposal with "proposal exceeds changed-path budget" on every attempt, for every
 * dependency-based Objective, with no possible recovery by retrying. gitRun() now ensures a .gitignore
 * covering common dependency/build artifact directories exists before any blanket `add -A`, exactly like
 * it already deterministically configures git identity right after `init` -- environment setup, not a
 * cognitive decision, fixed once at the single chokepoint that protects every future Objective through
 * this path.
 */
class GeneralWorkspaceDependencyArtifactGitignoreTest {
    private static final String WORKER = "WORKER-GENERAL-ENGINEERING";
    private static final String AUTH = "authorization:test:general-gitignore-guard";
    private static final String OBJECTIVE = "objective:test:general-gitignore-guard";
    private static final String ASSIGNMENT = "assignment:test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T08:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temp;

    @Test
    void blanketAddEnsuresDependencyArtifactsAreGitignoredFirst() throws Exception {
        List<Map.Entry<String, List<String>>> recorded = new CopyOnWriteArrayList<>();
        HttpServer server = recordingSandboxServer(recorded);
        try {
            Harness harness = new Harness(temp, server);
            Files.createDirectories(harness.workspace.path().resolve("node_modules").resolve("esbuild"));
            Files.writeString(harness.workspace.path().resolve("node_modules").resolve("esbuild").resolve("bin"), "x");

            ActionFabric.ActionObservation observation = harness.fabric.execute(
                    harness.request("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")),
                    harness.permit("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")));

            assertTrue(observation.success());
            Path gitignore = harness.workspace.path().resolve(".gitignore");
            assertTrue(Files.isRegularFile(gitignore), "a .gitignore must be created before the blanket add");
            String content = Files.readString(gitignore);
            assertTrue(content.contains("node_modules/"), "node_modules must be excluded: " + content);
            assertTrue(content.contains("dist/") && content.contains("__pycache__/"),
                    "other common dependency/build artifact directories must also be excluded: " + content);
            // The .gitignore write itself is a direct host-side workspace write (like any other produced
            // source file), not a sandboxed subprocess call, so it must never appear as an extra recorded
            // sandbox command -- only the originally requested add.
            assertEquals(1, recorded.size());
            assertEquals(List.of("add", "-A"), recorded.get(0).getValue());
        } finally {
            server.stop(0);
            ExecutionAttemptContext.clear();
        }
    }

    @Test
    void narrowAddOfASpecificPathNeverTouchesGitignore() throws Exception {
        List<Map.Entry<String, List<String>>> recorded = new CopyOnWriteArrayList<>();
        HttpServer server = recordingSandboxServer(recorded);
        try {
            Harness harness = new Harness(temp, server);

            ActionFabric.ActionObservation observation = harness.fabric.execute(
                    harness.request("workspace.git.run", Map.of("argsJson", "[\"add\",\"README.md\"]")),
                    harness.permit("workspace.git.run", Map.of("argsJson", "[\"add\",\"README.md\"]")));

            assertTrue(observation.success());
            assertTrue(Files.notExists(harness.workspace.path().resolve(".gitignore")),
                    "a narrow, already-scoped add must not trigger the blanket-add guard");
            assertEquals(1, recorded.size());
        } finally {
            server.stop(0);
            ExecutionAttemptContext.clear();
        }
    }

    @Test
    void anExistingGitignoreThatAlreadyCoversDependencyArtifactsIsLeftUntouched() throws Exception {
        List<Map.Entry<String, List<String>>> recorded = new CopyOnWriteArrayList<>();
        HttpServer server = recordingSandboxServer(recorded);
        try {
            Harness harness = new Harness(temp, server);
            String custom = "# project-specific\nnode_modules/\n*.log\n";
            Files.writeString(harness.workspace.path().resolve(".gitignore"), custom);

            ActionFabric.ActionObservation observation = harness.fabric.execute(
                    harness.request("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")),
                    harness.permit("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")));

            assertTrue(observation.success());
            String content = Files.readString(harness.workspace.path().resolve(".gitignore"));
            assertTrue(content.contains("*.log"), "a Human/cognition-authored custom entry must be preserved: " + content);
        } finally {
            server.stop(0);
            ExecutionAttemptContext.clear();
        }
    }

    private static HttpServer recordingSandboxServer(List<Map.Entry<String, List<String>>> recorded) throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            List<String> args = new ArrayList<>();
            request.path("args").forEach(node -> args.add(node.asText()));
            recorded.add(Map.entry(request.path("executable").asText(), args));
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("success", true);
            body.put("exitCode", 0);
            body.put("timedOut", false);
            body.put("outputTruncated", false);
            body.put("output", "");
            body.put("workspaceKey", request.path("workspaceKey").asText());
            body.put("executable", request.path("executable").asText());
            body.put("durationMillis", 10);
            body.put("attemptId", request.path("attemptId").asText());
            body.put("attemptFencingToken", request.path("attemptFencingToken").asLong());
            body.put("workspaceRef", request.path("workspaceRef").asText());
            byte[] response = json.writeValueAsBytes(body);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static final class Harness {
        private final ActionFabric fabric;
        private final GovernanceTestHarness governance;
        private final GovernanceTestHarness.BoundMutation mutation;
        private final ObjectiveWorkspaceService.ObjectiveWorkspace workspace;

        private Harness(Path root, HttpServer server) {
            ObjectMapper json = new ObjectMapper();
            ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(root.resolve("workspaces"));
            WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
            profiles.bind(WORKER, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                    "execution.general.workspace", CLOCK.instant());

            HttpClient http = HttpClient.newHttpClient();
            WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                    http, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "sandbox-test-token", profiles, workspaces, json);
            RepositoryWorkspaceMaterializationService repositories =
                    new RepositoryWorkspaceMaterializationService(http, "", workspaces, json);
            GitHubWorkspaceProposalPublisher proposals =
                    new GitHubWorkspaceProposalPublisher(http, "", workspaces, sandbox, json);
            GeneralWorkspaceActionCatalog catalog =
                    new GeneralWorkspaceActionCatalog(workspaces, sandbox, profiles, repositories, proposals, json);

            governance = new GovernanceTestHarness(CLOCK);
            ExecutionWorkSpec work = new ExecutionWorkSpec(
                    "step:test", "stage and commit a fresh application workspace",
                    "kelvinka38/metatron-workforce", "execution.general.workspace", List.of(),
                    ExecutionWorkSpec.Consequence.MUTATING,
                    List.of("workspace changes are staged and committed"),
                    List.of("workspace git evidence"));
            mutation = governance.bind(OBJECTIVE, "founder-test", WORKER, ASSIGNMENT, AUTH, "runtime:test", work);
            fabric = new ActionFabric(catalog.actions(WORKER, AUTH, OBJECTIVE), governance.gate);
            workspace = workspaces.provision(OBJECTIVE, WORKER);
        }

        private ActionFabric.ActionRequest request(String actionRef, Map<String, String> inputs) {
            return new ActionFabric.ActionRequest(
                    actionRef, WORKER, ASSIGNMENT, AUTH, OBJECTIVE, "step:test", "idempotency:test", true, inputs);
        }

        private com.metatron.workforce.execution.governance.ExecutionPermit permit(
                String actionRef, Map<String, String> inputs) {
            return governance.permit(mutation, OBJECTIVE, WORKER, ASSIGNMENT, AUTH, "step:test", actionRef, inputs);
        }
    }
}
