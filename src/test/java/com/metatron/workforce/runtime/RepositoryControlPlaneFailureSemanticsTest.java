package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** RC-04: institutional repository provisioning failure is typed and cannot become creative Worker recovery. */
final class RepositoryControlPlaneFailureSemanticsTest {
    @TempDir Path temp;

    @Test
    void missingRepositoryCredentialFailsAsTypedInstitutionalBlockerBeforeAnyWorkerAction() {
        RepositoryWorkspaceMaterializationService service = new RepositoryWorkspaceMaterializationService(
                HttpClient.newHttpClient(), "", new ObjectiveWorkspaceService(temp), new ObjectMapper());

        GovernanceDeniedException failure = assertThrows(GovernanceDeniedException.class, () ->
                service.materialize("WORKER-GENERAL-ENGINEERING", "objective-rc04",
                        "kelvinka38/metatron-workforce", "main"));

        assertEquals("REPOSITORY_CONTROL_PLANE_UNAVAILABLE", failure.code());
    }
}
