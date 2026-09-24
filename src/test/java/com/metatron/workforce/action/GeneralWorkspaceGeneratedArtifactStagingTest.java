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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for production Objective case-756fb2fa: install/build outputs physically existed and a
 * deterministic `git add -A` committed 272 node_modules paths plus dist, so proposal publication
 * correctly rejected the 281-path delta.
 */
class GeneralWorkspaceGeneratedArtifactStagingTest {
    private static final String WORKER = "WORKER-GENERAL-ENGINEERING";
    private static final String AUTH = "authorization:test:generated-artifact-staging";
    private static final String OBJECTIVE = "objective:test:generated-artifact-staging";
    private static final String ASSIGNMENT = "assignment:test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temp;

    @Test
    void realisticFreshNodeReactLifecycleCommitsSourceButNotPhysicalInstallOrBuildOutputs() throws Exception {
        try (Harness harness = new Harness(temp.resolve("fresh"), OBJECTIVE)) {
            String baseline = harness.initializeBaseline(false);
            Path root = harness.root();
            writeFreshReactProject(root);

            assertTrue(harness.execute("workspace.dependencies.install", Map.of()).success());
            assertTrue(harness.execute("workspace.build.run", Map.of()).success());
            assertTrue(harness.execute("workspace.test.run", Map.of()).success());
            assertTrue(Files.isDirectory(root.resolve("node_modules/react")));
            assertTrue(Files.isRegularFile(root.resolve("dist/index.html")));

            // Make the historical raw boundary exceed the real proposal budget, then unstage it.
            for (int i = 0; i < 60; i++) {
                write(root.resolve("node_modules/generated/file-" + i + ".js"), "generated " + i);
            }
            run(root, "git", "add", "-A");
            List<String> polluted = lines(run(root, "git", "diff", "--cached", "--name-only"));
            assertTrue(polluted.size() > 50, "pre-fix blanket staging must reproduce the proposal-budget failure");
            assertTrue(polluted.stream().anyMatch(path -> path.startsWith("node_modules/")));
            assertTrue(polluted.stream().anyMatch(path -> path.startsWith("dist/")));
            run(root, "git", "reset", "-q");

            assertTrue(harness.execute("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")).success());
            assertTrue(harness.execute("workspace.git.run",
                    Map.of("argsJson", "[\"commit\",\"-m\",\"fresh application\"]")).success());

            List<String> committed = lines(run(root, "git", "diff", "--name-only", "--no-renames",
                    baseline + "..HEAD"));
            assertTrue(committed.contains("src/App.jsx"));
            assertTrue(committed.contains("package.json"));
            assertTrue(committed.contains("package-lock.json"));
            assertTrue(committed.contains("build.mjs"));
            assertTrue(committed.contains("test/app.test.js"));
            assertFalse(committed.stream().anyMatch(path -> path.startsWith("node_modules/")));
            assertFalse(committed.stream().anyMatch(path -> path.startsWith("dist/")));
            assertTrue(committed.size() < 50, "governed source proposal must remain below policy");
            assertEquals("", run(root, "git", "status", "--porcelain").trim(),
                    "generated artifacts must remain physical but ignored so publication sees a clean workspace");
        } finally {
            ExecutionAttemptContext.clear();
        }
    }

    @Test
    void generatedReactScaffoldCarriesProjectHygieneWithoutModelRememberingIt() throws Exception {
        try (Harness harness = new Harness(temp.resolve("scaffold"), OBJECTIVE + ":scaffold")) {
            write(harness.root().resolve("src/App.jsx"),
                    "export default function App(){return <main>Workforce</main>}\n");

            ActionFabric.ActionObservation prepared = harness.execute("workspace.project.prepare", Map.of());

            assertTrue(prepared.success());
            String ignore = Files.readString(harness.root().resolve(".gitignore"));
            assertTrue(ignore.contains("node_modules/"));
            assertTrue(ignore.contains("dist/"));
            assertTrue(ignore.contains("build/"));
            assertTrue(ignore.contains("target/"));
        } finally {
            ExecutionAttemptContext.clear();
        }
    }

    @Test
    void alreadyTrackedGeneratedConventionRemainsAuthoritativeWhileNewSiblingsStayExcluded() throws Exception {
        try (Harness harness = new Harness(temp.resolve("tracked"), OBJECTIVE + ":tracked")) {
            String baseline = harness.initializeBaseline(true);
            Path root = harness.root();
            write(root.resolve("dist/intentional.txt"), "tracked update\n");
            write(root.resolve("dist/new-build-output.js"), "new generated sibling\n");
            write(root.resolve("node_modules/pkg/index.js"), "dependency output\n");
            write(root.resolve("src/App.js"), "export const answer = 42;\n");

            assertTrue(harness.execute("workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]")).success());
            assertTrue(harness.execute("workspace.git.run",
                    Map.of("argsJson", "[\"commit\",\"-m\",\"tracked generated path update\"]")).success());

            List<String> committed = lines(run(root, "git", "diff", "--name-only", baseline + "..HEAD"));
            assertTrue(committed.contains("dist/intentional.txt"),
                    "git-tracked content must remain stageable regardless of directory convention");
            assertTrue(committed.contains("src/App.js"));
            assertFalse(committed.contains("dist/new-build-output.js"));
            assertFalse(committed.stream().anyMatch(path -> path.startsWith("node_modules/")));
            assertEquals("", run(root, "git", "status", "--porcelain").trim());
        } finally {
            ExecutionAttemptContext.clear();
        }
    }

    private static void writeFreshReactProject(Path root) throws Exception {
        write(root.resolve("src/App.jsx"),
                "import React from 'react'; export default function App(){return <main>Workforce</main>}\n");
        write(root.resolve("fixtures/react/package.json"),
                "{\"name\":\"react\",\"version\":\"18.3.1\",\"main\":\"index.js\"}\n");
        write(root.resolve("fixtures/react/index.js"), "export default {};\n");
        write(root.resolve("fixtures/react-dom/package.json"),
                "{\"name\":\"react-dom\",\"version\":\"18.3.1\",\"main\":\"index.js\"}\n");
        write(root.resolve("fixtures/react-dom/index.js"), "export default {};\n");
        write(root.resolve("build.mjs"),
                "import {mkdirSync,writeFileSync} from 'node:fs';"
                        + "mkdirSync('dist',{recursive:true});"
                        + "writeFileSync('dist/index.html','<!doctype html><main>built</main>');\n");
        write(root.resolve("test/app.test.js"),
                "import test from 'node:test';import assert from 'node:assert/strict';"
                        + "import {existsSync} from 'node:fs';"
                        + "test('source exists',()=>assert.ok(existsSync('src/App.jsx')));\n");
        write(root.resolve("package.json"), """
                {
                  "name": "metatron-generated-react-app",
                  "version": "1.0.0",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "build": "node build.mjs",
                    "test": "node --test test/*.test.js"
                  },
                  "dependencies": {
                    "react": "file:fixtures/react",
                    "react-dom": "file:fixtures/react-dom"
                  }
                }
                """);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static List<String> lines(String output) {
        return output.lines().filter(line -> !line.isBlank()).toList();
    }

    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "command timed out: " + List.of(command));
        String text = new String(output, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), () -> "command failed: " + List.of(command) + "\n" + text);
        return text;
    }

    private static final class Harness implements AutoCloseable {
        private final Path root;
        private final String objective;
        private final HttpServer server;
        private final ActionFabric fabric;
        private final GovernanceTestHarness governance;
        private final GovernanceTestHarness.BoundMutation mutation;

        private Harness(Path testRoot, String objective) throws Exception {
            this.objective = objective;
            ObjectMapper json = new ObjectMapper();
            ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(testRoot.resolve("workspaces"));
            ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objective, WORKER);
            this.root = workspace.path();
            this.server = executionSandbox(root, json);

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
                    "step:test", "build, verify and commit a fresh React application",
                    "kelvinka38/metatron-workforce", "execution.general.workspace", List.of(),
                    ExecutionWorkSpec.Consequence.MUTATING,
                    List.of("source-only committed delta"), List.of("git evidence"));
            mutation = governance.bind(objective, "founder-test", WORKER, ASSIGNMENT, AUTH,
                    "runtime:test", work);
            fabric = new ActionFabric(catalog.actions(WORKER, AUTH, objective), governance.gate);
        }

        private String initializeBaseline(boolean trackedDist) throws Exception {
            run(root, "git", "init", "-q");
            run(root, "git", "config", "user.name", "Metatron Workforce");
            run(root, "git", "config", "user.email", "workforce@metatron.local");
            write(root.resolve("README.md"), "baseline\n");
            if (trackedDist) write(root.resolve("dist/intentional.txt"), "baseline tracked output\n");
            run(root, "git", "add", "-A");
            run(root, "git", "commit", "-q", "-m", "baseline");
            return run(root, "git", "rev-parse", "HEAD").trim();
        }

        private Path root() {
            return root;
        }

        private ActionFabric.ActionObservation execute(String actionRef, Map<String, String> inputs) {
            ActionFabric.ActionRequest request = new ActionFabric.ActionRequest(
                    actionRef, WORKER, ASSIGNMENT, AUTH, objective, "step:test",
                    "idempotency:" + actionRef, true, inputs);
            if (fabric.consequenceOf(actionRef) == ActionFabric.Consequence.MUTATING) {
                return fabric.execute(request,
                        governance.permit(mutation, objective, WORKER, ASSIGNMENT, AUTH,
                                "step:test", actionRef, inputs));
            }
            return fabric.execute(request);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static HttpServer executionSandbox(Path root, ObjectMapper json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", exchange -> {
            Path outputFile = null;
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                List<String> command = new ArrayList<>();
                command.add(request.path("executable").asText());
                request.path("args").forEach(node -> command.add(node.asText()));

                String relative = request.path("workingDirectory").asText("");
                if (relative.equals("repos/primary")) relative = "";
                else if (relative.startsWith("repos/primary/")) relative = relative.substring("repos/primary/".length());
                Path working = relative.isBlank() ? root : root.resolve(relative).normalize();
                if (!working.startsWith(root)) throw new SecurityException("test sandbox path escaped");

                outputFile = Files.createTempFile("metatron-sandbox-command-", ".log");
                Process process = new ProcessBuilder(command)
                        .directory(working.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .start();
                boolean finished = process.waitFor(90, TimeUnit.SECONDS);
                if (!finished) process.destroyForcibly();
                String output = Files.readString(outputFile);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", finished && process.exitValue() == 0);
                response.put("exitCode", finished ? process.exitValue() : -1);
                response.put("timedOut", !finished);
                response.put("outputTruncated", false);
                response.put("output", output);
                response.put("workspaceKey", request.path("workspaceKey").asText());
                response.put("executable", request.path("executable").asText());
                response.put("durationMillis", 1);
                response.put("attemptId", request.path("attemptId").asText());
                response.put("attemptFencingToken", request.path("attemptFencingToken").asLong());
                response.put("workspaceRef", request.path("workspaceRef").asText());
                byte[] body = json.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception failure) {
                byte[] body = ("{\"error\":\"" + failure.getClass().getSimpleName() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                if (outputFile != null) Files.deleteIfExists(outputFile);
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
