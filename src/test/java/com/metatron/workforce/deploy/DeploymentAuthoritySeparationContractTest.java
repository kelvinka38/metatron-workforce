package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class DeploymentAuthoritySeparationContractTest {
    @Test
    void legacyDeployEntrypointsCannotCreateAnAlternateComposeMutationLane() throws Exception {
        String shell = Files.readString(Path.of("deploy/deploy.sh"));
        String powershell = Files.readString(Path.of("deploy/deploy.ps1"));

        assertTrue(shell.contains("deploy-production-sha.sh"));
        assertFalse(shell.contains("docker compose"));
        assertFalse(shell.contains(" up -d"));

        assertTrue(powershell.contains("retired as a production mutation path"));
        assertTrue(powershell.contains("deploy-production-sha.sh"));
        assertFalse(powershell.contains("docker compose"));
        assertFalse(powershell.contains("gradlew"));
    }

    @Test
    void workforceAndMcpReleaseIngressesAreSeparated() throws Exception {
        String workforce = Files.readString(Path.of(".github/workflows/production-deploy.yml"));
        String mcp = Files.readString(Path.of(".github/workflows/mcp-host-commander-release.yml"));

        assertTrue(workforce.contains("'scripts/**'"));
        assertTrue(workforce.contains("if [[ \"$path\" == scripts/mcp/* ]]"));
        assertTrue(workforce.contains("mcp_release=true"));
        assertTrue(workforce.contains("workforce_release=true"));
        assertFalse(workforce.contains("release-host-commander-g19.sh"));
        assertTrue(workforce.contains("--kind workforce-build"));
        assertTrue(workforce.contains("--kind workforce-deploy"));
        assertTrue(workforce.contains("--kind mcp-host-commander-release"));

        String registry = Files.readString(Path.of("highway/task-registry.json"));
        String highwayTask = Files.readString(Path.of("scripts/highway-tasks/mcp-host-commander-release.sh"));

        assertTrue(mcp.contains("workflow_dispatch"));
        assertTrue(mcp.contains("--kind mcp-host-commander-release"));
        assertTrue(mcp.contains("$GITHUB_SHA"));
        assertFalse(mcp.contains("release-host-commander-g19.sh"));

        assertTrue(registry.contains("\"mcp-host-commander-release\""));
        assertTrue(registry.contains("scripts/highway-tasks/mcp-host-commander-release.sh"));
        assertTrue(highwayTask.contains("scripts/mcp/release-host-commander-g19.sh"));
        assertTrue(highwayTask.contains("$HIGHWAY_SOURCE_SHA"));
    }
}
