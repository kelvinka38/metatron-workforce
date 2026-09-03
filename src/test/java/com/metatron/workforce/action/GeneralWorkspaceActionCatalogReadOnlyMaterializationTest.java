package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspaceActionCatalogReadOnlyMaterializationTest {
    @TempDir
    Path tempDir;

    @Test
    void repositoryMaterializationRemainsAvailableToReadOnlyRepositoryWork() {
        String worker = "WORKER-GENERAL-READONLY";
        String authorization = "authorization:test:general-readonly";
        String objective = "objective:test:general-readonly";

        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(tempDir.resolve("workspaces"));
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        profiles.bind(worker,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "execution.general.workspace",
                Instant.now());

        ObjectMapper json = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                http,
                URI.create("http://127.0.0.1:1"),
                "test-sandbox-token",
                profiles,
                workspaces,
                json);
        RepositoryWorkspaceMaterializationService repositories =
                new RepositoryWorkspaceMaterializationService(http, "", workspaces, json);

        GeneralWorkspaceActionCatalog catalog = new GeneralWorkspaceActionCatalog(
                workspaces, sandbox, profiles, repositories, json);
        ActionFabric fabric = new ActionFabric(catalog.actions(worker, authorization, objective));

        assertTrue(fabric.catalogFor(worker, authorization, false)
                .contains("workspace.repository.materialize"));
    }
}
