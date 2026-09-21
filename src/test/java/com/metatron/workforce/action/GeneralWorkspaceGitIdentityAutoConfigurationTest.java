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
 * Production incident: DELIVER repeatedly exhausted its cognitive cycle budget because a fresh
 * new-application workspace (no prior repository-materialization baseline) ran a bare `git init`
 * with no follow-up author identity, so every subsequent `git commit` failed identically with
 * "Author identity unknown" until the cycle budget ran out. `git config user.name`/`user.email`
 * are deterministic infrastructure, not a cognitive decision, so `gitRun()` must configure them
 * itself, immediately after a successful `init`, at zero extra cognitive-cycle cost.
 */
class GeneralWorkspaceGitIdentityAutoConfigurationTest {
    private static final String WORKER = "WORKER-GENERAL-ENGINEERING";
    private static final String AUTH = "authorization:test:general-git-identity";
    private static final String OBJECTIVE = "objective:test:general-git-identity";
    private static final String ASSIGNMENT = "assignment:test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T08:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temp;

    @Test
    void gitInitIsImmediatelyFollowedByAutomaticAuthorIdentityConfiguration() throws Exception {
        List<Map.Entry<String, List<String>>> recorded = new CopyOnWriteArrayList<>();
        HttpServer server = recordingSandboxServer(recorded);
        try {
            Harness harness = new Harness(temp, server);
            ActionFabric.ActionObservation observation = harness.fabric.execute(
                    harness.request("workspace.git.run", Map.of("argsJson", "[\"init\"]")),
                    harness.permit("workspace.git.run", Map.of("argsJson", "[\"init\"]")));

            assertTrue(observation.success());
            assertEquals(3, recorded.size(),
                    "a successful init must be immediately followed by user.name and user.email configuration");
            assertEquals(List.of("init"), recorded.get(0).getValue());
            assertEquals(List.of("config", "user.name", "Metatron Workforce"), recorded.get(1).getValue());
            assertEquals(List.of("config", "user.email", "workforce@metatron.local"), recorded.get(2).getValue());
            assertTrue(recorded.stream().allMatch(entry -> entry.getKey().equals("git")));
        } finally {
            server.stop(0);
            // GovernanceTestHarness.bind() binds ExecutionAttemptContext on this test thread and only
            // an explicit ExecutionAttemptService.succeed()/fail() call clears it; this test never
            // reaches either, so without an explicit clear the binding leaks onto whichever later
            // test Gradle's worker pool reuses this same JVM thread for, causing that unrelated test's
            // own sandbox mock (which doesn't echo back an attemptId) to fail with an unrelated
            // "sandbox execution attempt attribution mismatch".
            ExecutionAttemptContext.clear();
        }
    }

    @Test
    void nonInitGitCommandsNeverTriggerIdentityConfiguration() throws Exception {
        List<Map.Entry<String, List<String>>> recorded = new CopyOnWriteArrayList<>();
        HttpServer server = recordingSandboxServer(recorded);
        try {
            Harness harness = new Harness(temp, server);
            ActionFabric.ActionObservation observation = harness.fabric.execute(
                    harness.request("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")),
                    harness.permit("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")));

            assertTrue(observation.success());
            assertEquals(1, recorded.size(), "only the requested command should run for a non-init git action");
            assertEquals(List.of("add", "-A"), recorded.get(0).getValue());
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
                    "step:test", "initialize and commit a fresh application workspace",
                    "kelvinka38/metatron-workforce", "execution.general.workspace", List.of(),
                    ExecutionWorkSpec.Consequence.MUTATING,
                    List.of("repository is initialized with a configured author identity"),
                    List.of("workspace git evidence"));
            mutation = governance.bind(OBJECTIVE, "founder-test", WORKER, ASSIGNMENT, AUTH, "runtime:test", work);
            fabric = new ActionFabric(catalog.actions(WORKER, AUTH, OBJECTIVE), governance.gate);
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
