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
        assertTrue(workforce.contains("'!scripts/mcp/**'"));
        assertFalse(workforce.contains("release-host-commander-g19.sh"));
        assertTrue(workforce.contains("--kind workforce-build"));
        assertTrue(workforce.contains("--kind workforce-deploy"));

        assertTrue(mcp.contains("'scripts/mcp/**'"));
        assertTrue(mcp.contains("release-host-commander-g19.sh"));
        assertTrue(mcp.contains("$GITHUB_SHA"));
        assertFalse(mcp.contains("workforce-deploy"));
    }
}
