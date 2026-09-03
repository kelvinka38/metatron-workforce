package com.metatron.workforce.action;

import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneralWorkspaceMaterializationReuseTest {
    private static final String SHA = "fee4ecb7009ce0a549ed0be96caa6c03ac28d9d3";

    @TempDir
    Path tempDir;

    @Test
    void sameRepositoryAndRefReusesExistingImmutableProvenance() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(tempDir.resolve("workspaces"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        workspaces.write(workspace, "README.md", "source content");
        workspaces.write(workspace, ".metatron-repository",
                "repository=kelvinka38/metatron-workforce\nrequestedRef=" + SHA + "\ncommitSha=" + SHA + "\n");

        RepositoryWorkspaceMaterializationService.MaterializedRepository existing =
                GeneralWorkspaceActionCatalog.existingMaterialization(
                        workspaces, workspace, "kelvinka38/metatron-workforce", SHA);

        assertEquals("kelvinka38/metatron-workforce", existing.repository());
        assertEquals(SHA, existing.requestedRef());
        assertEquals(SHA, existing.resolvedCommitSha());
        assertEquals(1, existing.files());
        assertEquals("source content".length(), existing.bytes());
    }

    @Test
    void immutableCommitShaIsAcceptedAsEquivalentRetryRef() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(tempDir.resolve("equivalent"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        workspaces.write(workspace, "README.md", "source content");
        workspaces.write(workspace, ".metatron-repository",
                "repository=kelvinka38/metatron-workforce\nrequestedRef=main\ncommitSha=" + SHA + "\n");

        RepositoryWorkspaceMaterializationService.MaterializedRepository existing =
                GeneralWorkspaceActionCatalog.existingMaterialization(
                        workspaces, workspace, "kelvinka38/metatron-workforce", SHA);

        assertEquals(SHA, existing.resolvedCommitSha());
    }

    @Test
    void differentRepositoryOrRefCannotReuseWorkspace() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(tempDir.resolve("conflict"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        workspaces.write(workspace, "README.md", "source content");
        workspaces.write(workspace, ".metatron-repository",
                "repository=kelvinka38/metatron-workforce\nrequestedRef=" + SHA + "\ncommitSha=" + SHA + "\n");

        assertThrows(IllegalStateException.class, () -> GeneralWorkspaceActionCatalog.existingMaterialization(
                workspaces, workspace, "kelvinka38/bios", SHA));
        assertThrows(IllegalStateException.class, () -> GeneralWorkspaceActionCatalog.existingMaterialization(
                workspaces, workspace, "kelvinka38/metatron-workforce", "main"));
    }
}
