package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ObjectiveWorkspaceAndProfileClosureTest {
    @TempDir Path temp;

    @Test
    void objectiveWorkspaceIsWritableDurableAndTraversalSafe() throws Exception {
        ObjectiveWorkspaceService service = new ObjectiveWorkspaceService(temp.resolve("workspaces"));
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = service.provision("objective-1", "worker-1");

        service.write(workspace, "src/main.txt", "real work");
        assertEquals("real work", service.read(workspace, "src/main.txt"));
        assertTrue(service.list(workspace, "").contains("src/main.txt"));
        assertThrows(SecurityException.class, () -> service.write(workspace, "../../escape.txt", "denied"));
        assertFalse(Files.exists(temp.resolve("escape.txt")));

        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Path link = workspace.path().resolve("link.txt");
        Files.createSymbolicLink(link, outside);
        assertThrows(SecurityException.class, () -> service.read(workspace, "link.txt"));
        assertThrows(SecurityException.class, () -> service.write(workspace, "link.txt", "overwrite denied"));
        assertEquals("secret", Files.readString(outside));
    }

    @Test
    void formedWorkerRuntimeProfilePersistsAsUsableToolProfile() {
        Path state = temp.resolve("runtime-profile-bindings.tsv");
        WorkerRuntimeProfileBindingService first = new WorkerRuntimeProfileBindingService(state);
        first.bind("WORKER-GENERAL-ENGINEERING",
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "execution.general.workspace", Instant.parse("2026-09-03T00:00:00Z"));

        WorkerRuntimeProfileBindingService restarted = new WorkerRuntimeProfileBindingService(state);
        WorkerRuntimeProfileBindingService.Binding binding = restarted.requireBinding("WORKER-GENERAL-ENGINEERING");
        assertEquals(WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE, binding.profile().profileRef());
        assertTrue(binding.profile().writableWorkspace());
        assertTrue(binding.profile().actionRefs().contains("research.web.search"));
        assertTrue(binding.profile().actionRefs().contains("workspace.repository.materialize"));
        assertTrue(binding.profile().actionRefs().contains("workspace.file.write"));
        assertTrue(binding.profile().actionRefs().contains("workspace.git.run"));
        assertTrue(binding.profile().actionRefs().contains("workspace.build.run"));
        assertTrue(binding.profile().actionRefs().contains("workspace.test.run"));
        assertTrue(binding.profile().allowedExecutables().contains("git"));
        assertTrue(binding.profile().allowedExecutables().contains("./gradlew"));
    }
}
