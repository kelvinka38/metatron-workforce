package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryWorkspaceMaterializationStateTest {
    @TempDir Path temp;

    @Test
    void missingCompletionRefIsIncomplete() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("missing"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        assertEquals("", RepositoryWorkspaceMaterializationState.completedBaselineSha(workspaces, workspace));
    }

    @Test
    void validCompletionRefReturnsImmutableBaseline() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("valid"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        String sha = "abcdefabcdefabcdefabcdefabcdefabcdefabcd";
        workspaces.write(workspace, RepositoryWorkspaceMaterializationState.BASELINE_REF_PATH, sha + "\n");
        assertEquals(sha, RepositoryWorkspaceMaterializationState.completedBaselineSha(workspaces, workspace));
    }

    @Test
    void malformedCompletionRefFailsClosed() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("invalid"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        workspaces.write(workspace, RepositoryWorkspaceMaterializationState.BASELINE_REF_PATH, "not-a-sha\n");
        assertThrows(IllegalStateException.class,
                () -> RepositoryWorkspaceMaterializationState.completedBaselineSha(workspaces, workspace));
    }
}
