package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RC-01: repository credentials/authentication are infrastructure, never Worker/planner Work. */
final class RepositoryControlPlaneWorkContractTest {

    @Test
    void rejectsGithubConnectLoginAuthAndCredentialProvisioningAsWork() {
        for (String capability : List.of(
                "UNAVAILABLE:github-connect",
                "UNAVAILABLE:github-login",
                "UNAVAILABLE:github-oauth",
                "UNAVAILABLE:github-token",
                "UNAVAILABLE:repository-auth",
                "UNAVAILABLE:repository-credential")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                    new ExecutionWorkSpec(
                            "bad-step", "Connect GitHub so the Worker can continue", "kelvinka38/metatron-workforce",
                            capability, List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                            List.of("GitHub connected"), List.of("connection evidence")));
            assertTrue(failure.getMessage().startsWith("repository-control-plane-dependency-cannot-be-work-step:"));
        }
    }

    @Test
    void governedRepositoryActionsRemainValidWork() {
        assertDoesNotThrow(() -> new ExecutionWorkSpec(
                "repo-work", "Repair source and publish a reviewable pull request", "kelvinka38/metatron-workforce",
                "execution.general.workspace", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "reviewable pull request exists"),
                List.of("workspace.test.run evidence", "workspace.github.pr.publish evidence")));

        assertDoesNotThrow(() -> new ExecutionWorkSpec(
                "repo-audit", "Audit repository read-only", "kelvinka38/bios",
                "repository.audit.read", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("audit complete"), List.of("repository evidence")));
    }

    @Test
    void githubPrPublicationIsAnExecutionEffectNotCredentialProvisioning() {
        assertDoesNotThrow(() -> new ExecutionWorkSpec(
                "publish-pr", "Publish committed Objective work product as unmerged GitHub pull request",
                "kelvinka38/metatron-workforce", "workspace.github.pr.publish", List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("reviewable unmerged pull request exists"), List.of("GitHub PR evidence")));
    }
}
