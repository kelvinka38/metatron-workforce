package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryPullRequestAutonomousCapabilityTest {
    @Test
    void mutatesOnlyStableGs2SentinelToIdempotencyBoundProbe() {
        String source = """
                # GS2 fixture
                canonical-main-remains-unchanged
                GS2_AUTONOMOUS_PROBE=UNSET
                """;
        String probe = "0123456789abcdef";
        String mutated = RepositoryPullRequestAutonomousCapability.applyProbe(source, probe);

        assertTrue(mutated.contains("GS2_AUTONOMOUS_PROBE=" + probe));
        assertTrue(mutated.contains("canonical-main-remains-unchanged"));
        assertFalse(mutated.contains(RepositoryPullRequestAutonomousCapability.UNSET_SENTINEL));
        assertTrue(RepositoryPullRequestAutonomousCapability.hasProbe(mutated, probe));
    }

    @Test
    void rejectsMalformedOrAlreadyConsumedCanonicalFixture() {
        assertThrows(IllegalStateException.class, () ->
                RepositoryPullRequestAutonomousCapability.applyProbe("no sentinel", "0123456789abcdef"));
        assertThrows(IllegalStateException.class, () ->
                RepositoryPullRequestAutonomousCapability.applyProbe(
                        "GS2_AUTONOMOUS_PROBE=UNSET\nGS2_AUTONOMOUS_PROBE=UNSET", "0123456789abcdef"));
        assertThrows(IllegalArgumentException.class, () ->
                RepositoryPullRequestAutonomousCapability.applyProbe(
                        "GS2_AUTONOMOUS_PROBE=UNSET", "not-a-probe"));
    }

    @Test
    void refusesReadOnlyOrWrongRepositoryBeforeAnyEffect() {
        var capability = new RepositoryPullRequestAutonomousCapability(
                HttpClient.newHttpClient(), new ObjectMapper(), "http://127.0.0.1:1", "test-token");
        ExecutionWorkSpec readOnly = new ExecutionWorkSpec("s1", "mutate GS2 fixture", "kelvinka38/metatron-workforce",
                RepositoryPullRequestAutonomousCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("PR remains unmerged"), List.of("github-pr"));
        var request = new AutonomousExecutionCapability.CapabilityRequest("founder", "organization:metatron", "obj-1",
                readOnly, RepositoryPullRequestAutonomousCapability.WORKER_ID, "assignment-1",
                RepositoryPullRequestAutonomousCapability.AUTHORIZATION_REFERENCE, "dispatch-1", 1);
        assertThrows(SecurityException.class, () -> capability.execute(request));

        ExecutionWorkSpec wrongRepo = new ExecutionWorkSpec("s2", "mutate GS2 fixture", "kelvinka38/metatron-institution",
                RepositoryPullRequestAutonomousCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("PR remains unmerged"), List.of("github-pr"));
        var wrong = new AutonomousExecutionCapability.CapabilityRequest("founder", "organization:metatron", "obj-2",
                wrongRepo, RepositoryPullRequestAutonomousCapability.WORKER_ID, "assignment-2",
                RepositoryPullRequestAutonomousCapability.AUTHORIZATION_REFERENCE, "dispatch-2", 1);
        assertThrows(SecurityException.class, () -> capability.execute(wrong));
    }
}
