package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class GitDeploymentHygieneContractTest {
    @Test
    void legacyLinuxDeployEntrypointDelegatesOnlyToCanonicalExactShaHighwayPath() throws Exception {
        String script = Files.readString(Path.of("deploy/deploy.sh"));
        assertTrue(script.contains("deploy-production-sha.sh"));
        assertTrue(script.contains("compatibility wrapper only"));
        assertFalse(script.contains("docker compose"));
        assertFalse(script.contains("up -d --build"));
        assertFalse(script.contains("gradlew"));
    }

    @Test
    void legacyWindowsDeployEntrypointCannotMutateProduction() throws Exception {
        String script = Files.readString(Path.of("deploy/deploy.ps1"));
        assertTrue(script.contains("retired as a production mutation path"));
        assertTrue(script.contains("deploy-production-sha.sh"));
        assertFalse(script.contains("docker compose"));
        assertFalse(script.contains("gradlew"));
    }

    @Test
    void branchHygieneDeletesOnlyMergedGovernedObjectiveBranchesAndDefaultsHistoricalSweepToDryRun() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/git-branch-hygiene.yml"));
        assertTrue(workflow.contains("types: [closed]"));
        assertTrue(workflow.contains("github.event.pull_request.merged == true"));
        assertTrue(workflow.contains("startsWith(github.event.pull_request.head.ref, 'metatron/objective-')"));
        assertTrue(workflow.contains("git merge-base --is-ancestor"));
        assertTrue(workflow.contains("default: false"));
        assertTrue(workflow.contains("if [[ \"$APPLY\" != \"true\" ]]"));
        assertFalse(workflow.contains("refs/remotes/origin/fix/"));
        assertFalse(workflow.contains("refs/remotes/origin/feat/"));
    }
}
