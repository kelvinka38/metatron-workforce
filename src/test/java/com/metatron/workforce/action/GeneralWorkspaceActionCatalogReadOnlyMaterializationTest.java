package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspaceActionCatalogReadOnlyMaterializationTest {
    @TempDir
    Path tempDir;

    @Test
    void readOnlyRepositoryWorkCanMaterializeBuildAndTestButNotMutateSourceWorkspace() {
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
        GitHubWorkspaceProposalPublisher proposals =
                new GitHubWorkspaceProposalPublisher(http, "", workspaces, sandbox, json);

        GeneralWorkspaceActionCatalog catalog = new GeneralWorkspaceActionCatalog(
                workspaces, sandbox, profiles, repositories, proposals, json);
        ActionFabric fabric = new ActionFabric(catalog.actions(worker, authorization, objective));
        List<String> readOnlyCatalog = fabric.catalogFor(worker, authorization, false);

        assertTrue(readOnlyCatalog.contains("workspace.repository.materialize"));
        assertTrue(readOnlyCatalog.contains("workspace.build.run"));
        assertTrue(readOnlyCatalog.contains("workspace.test.run"));
        assertTrue(readOnlyCatalog.contains("workspace.file.read"));
        assertTrue(readOnlyCatalog.contains("workspace.git.status"));
        assertFalse(readOnlyCatalog.contains("workspace.file.write"));
        assertFalse(readOnlyCatalog.contains("workspace.process.run"));
        assertFalse(readOnlyCatalog.contains("workspace.shell.run"));
        assertFalse(readOnlyCatalog.contains("workspace.git.run"));
    }
}
