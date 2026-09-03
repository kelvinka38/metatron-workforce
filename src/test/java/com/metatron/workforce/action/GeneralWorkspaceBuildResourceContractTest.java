package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspaceBuildResourceContractTest {
    private static final String WORKER = "WORKER-GENERAL-ENGINEERING";
    private static final String AUTHORIZATION = "authorization:test:general-build";
    private static final String OBJECTIVE = "objective:test:general-build";

    @TempDir
    Path tempDir;

    @Test
    void governedGradleTestUsesSingleWorkerAndNoPersistentDaemon() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        AtomicReference<String> capturedToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", exchange -> {
            capturedToken.set(exchange.getRequestHeaders().getFirst(WorkerExecutionSandboxService.TOKEN_HEADER));
            JsonNode request = json.readTree(exchange.getRequestBody());
            captured.set(request);
            byte[] response = json.writeValueAsBytes(Map.of(
                    "success", true,
                    "exitCode", 0,
                    "timedOut", false,
                    "outputTruncated", false,
                    "output", "BUILD SUCCESSFUL",
                    "workspaceKey", request.path("workspaceKey").asText(),
                    "executable", request.path("executable").asText(),
                    "durationMillis", 100));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(tempDir.resolve("workspaces"));
            var workspace = workspaces.provision(OBJECTIVE, WORKER);
            workspaces.write(workspace, "gradlew", "#!/bin/sh\n");
            WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
            profiles.bind(WORKER, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                    "execution.general.workspace", Instant.now());
            HttpClient http = HttpClient.newHttpClient();
            WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                    http, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "sandbox-test-token", profiles, workspaces, json);
            GeneralWorkspaceActionCatalog catalog = new GeneralWorkspaceActionCatalog(
                    workspaces, sandbox, profiles,
                    new RepositoryWorkspaceMaterializationService(http, "", workspaces, json), json);
            ActionFabric fabric = new ActionFabric(catalog.actions(WORKER, AUTHORIZATION, OBJECTIVE));

            ActionFabric.ActionObservation observation = fabric.execute(new ActionFabric.ActionRequest(
                    "workspace.test.run", WORKER, "assignment:test", AUTHORIZATION, OBJECTIVE,
                    "step:test", "idempotency:test", false, Map.of()));

            assertTrue(observation.success());
            JsonNode request = captured.get();
            assertNotNull(request);
            assertEquals("sandbox-test-token", capturedToken.get());
            assertEquals("./gradlew", request.path("executable").asText());
            assertEquals(List.of("--no-daemon", "--max-workers=1", "test"),
                    json.convertValue(request.path("args"), json.getTypeFactory()
                            .constructCollectionType(List.class, String.class)));
            assertEquals(300, request.path("timeoutSeconds").asInt());
        } finally {
            server.stop(0);
        }
    }
}
