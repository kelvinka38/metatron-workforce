package com.metatron.workforce.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralWorkspaceSearchPatchContractTest {
    private static final String WORKER = "WORKER-GENERAL-ENGINEERING";
    private static final String AUTH = "authorization:test:search-patch";
    private static final String OBJECTIVE = "objective:test:search-patch";

    @TempDir Path temp;

    @Test
    void searchFindsRelevantSourceAndPatchChangesOnlyExpectedOccurrence() throws Exception {
        Harness harness = new Harness(temp);
        harness.workspaces.write(harness.workspace, "src/main/App.java",
                "final class App {\n  String value() { return \"broken\"; }\n}\n");
        harness.workspaces.write(harness.workspace, "src/test/AppTest.java",
                "final class AppTest { String expected = \"fixed\"; }\n");

        ActionFabric.ActionObservation searched = harness.fabric.execute(request(
                "workspace.file.search", false,
                Map.of("query", "broken", "path", "src", "maxMatches", "20")));

        assertTrue(searched.success());
        assertEquals("1", searched.outputs().get("matchCount"));
        List<Map<String, Object>> matches = harness.json.readValue(
                searched.outputs().get("matchesJson"), new TypeReference<List<Map<String, Object>>>() {});
        assertEquals("src/main/App.java", matches.getFirst().get("path"));
        assertEquals(2, ((Number) matches.getFirst().get("line")).intValue());

        ActionFabric.ActionObservation patched = harness.fabric.execute(request(
                "workspace.file.patch", true,
                Map.of("path", "src/main/App.java", "oldText", "\"broken\"",
                        "newText", "\"fixed\"", "expectedOccurrences", "1")));

        assertTrue(patched.success());
        assertTrue(harness.workspaces.read(harness.workspace, "src/main/App.java").contains("return \"fixed\""));
        assertTrue(harness.workspaces.read(harness.workspace, "src/test/AppTest.java").contains("expected = \"fixed\""));
    }

    @Test
    void patchFailsClosedWhenObservedOccurrenceCountDoesNotMatch() {
        Harness harness = new Harness(temp);
        harness.workspaces.write(harness.workspace, "src/config.txt", "broken\nbroken\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                harness.fabric.execute(request(
                        "workspace.file.patch", true,
                        Map.of("path", "src/config.txt", "oldText", "broken",
                                "newText", "fixed", "expectedOccurrences", "1"))));

        assertTrue(failure.getMessage().contains("expected 1 occurrences but found 2"));
        assertEquals("broken\nbroken\n", harness.workspaces.read(harness.workspace, "src/config.txt"));
    }

    private static ActionFabric.ActionRequest request(String actionRef, boolean mutating, Map<String, String> inputs) {
        return new ActionFabric.ActionRequest(
                actionRef, WORKER, "assignment:test", AUTH, OBJECTIVE,
                "step:test", "idempotency:test", mutating, inputs);
    }

    private static final class Harness {
        private final ObjectMapper json = new ObjectMapper();
        private final ObjectiveWorkspaceService workspaces;
        private final ObjectiveWorkspaceService.ObjectiveWorkspace workspace;
        private final ActionFabric fabric;

        private Harness(Path root) {
            workspaces = new ObjectiveWorkspaceService(root.resolve("workspaces"));
            workspace = workspaces.provision(OBJECTIVE, WORKER);
            WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
            profiles.bind(WORKER, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                    "execution.general.workspace", Instant.now());

            HttpClient http = HttpClient.newHttpClient();
            WorkerExecutionSandboxService sandbox = new WorkerExecutionSandboxService(
                    http, URI.create("http://127.0.0.1:1"), "unused-token", profiles, workspaces, json);
            RepositoryWorkspaceMaterializationService repositories =
                    new RepositoryWorkspaceMaterializationService(http, "", workspaces, json);
            GitHubWorkspaceProposalPublisher proposals =
                    new GitHubWorkspaceProposalPublisher(http, "", workspaces, sandbox, json);
            GeneralWorkspaceActionCatalog catalog =
                    new GeneralWorkspaceActionCatalog(workspaces, sandbox, profiles, repositories, proposals, json);
            fabric = new ActionFabric(catalog.actions(WORKER, AUTH, OBJECTIVE));
        }
    }
}
