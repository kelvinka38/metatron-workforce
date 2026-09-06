package com.metatron.workforce.management;

import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneralWorkspaceAutonomousCapabilityContinuityTest {
    private static final String SOURCE_SHA = "1111111111111111111111111111111111111111";
    private static final String BASELINE_SHA = "2222222222222222222222222222222222222222";

    @TempDir Path temp;

    @Test
    void provenanceAloneNeverClaimsCompletedMaterialization() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("partial"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        workspaces.write(workspace, "README.md", "partial source");
        workspaces.write(workspace, ".metatron-repository",
                "repository=kelvinka38/metatron-workforce\nrequestedRef=" + SOURCE_SHA
                        + "\ncommitSha=" + SOURCE_SHA + "\n");

        Map<String, String> memory =
                GeneralWorkspaceAutonomousCapability.objectiveWorkspaceMemory(workspaces, workspace);

        assertEquals("false", memory.get("workspaceMaterialized"));
    }

    @Test
    void completedBaselineRefMakesMaterializationDurablyReusable() {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("complete"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision("objective", "worker");
        workspaces.write(workspace, "README.md", "complete source");
        workspaces.write(workspace, ".metatron-repository",
                "repository=kelvinka38/metatron-workforce\nrequestedRef=" + SOURCE_SHA
                        + "\ncommitSha=" + SOURCE_SHA + "\n");
        workspaces.write(workspace, ".git/refs/metatron/materialized", BASELINE_SHA + "\n");

        Map<String, String> memory =
                GeneralWorkspaceAutonomousCapability.objectiveWorkspaceMemory(workspaces, workspace);

        assertEquals("true", memory.get("workspaceMaterialized"));
        assertEquals("kelvinka38/metatron-workforce", memory.get("repository"));
        assertEquals(SOURCE_SHA, memory.get("sourceCommitSha"));
        assertEquals(BASELINE_SHA, memory.get("localBaselineCommitSha"));
    }
}
