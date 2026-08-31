package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryPullRequestAutonomousCapabilityTest {
    @Test
    void repairsOnlyKnownStaleAutonomyBaselineAndPreservesPartialVerdict() {
        String source = """
                **Baseline:** `metatron-workforce/main` at or after `954b5647c114a8321e8beffc122c82784ac065dd`
                **General autonomy verdict:** PARTIAL / NOT YET ACCEPTED
                | Management Runner | Caller/test drives transitions | Missing persistent self-driving runner |
                | Work Graph | Execution specs can express steps/dependencies | Missing durable versioned DAG and general ready-set scheduler |
                | Economy | Budget/cost concepts and tests exist | No L9 production enforcement/observability proof |
                """;
        String repaired = RepositoryPullRequestAutonomousCapability.repair(source,
                "30a9f952c6e062a32de598a93f9759dd9ee9e69c");
        assertTrue(repaired.contains("Persistent autonomous runner with wake/reconcile"));
        assertTrue(repaired.contains("Durable versioned DAG"));
        assertTrue(repaired.contains("L9 controls implemented/deployed"));
        assertTrue(repaired.contains("PARTIAL / NOT YET ACCEPTED"));
        assertFalse(repaired.contains("Missing persistent self-driving runner"));
    }

    @Test
    void refusesReadOnlyOrWrongRepositoryBeforeAnyEffect() {
        var capability = new RepositoryPullRequestAutonomousCapability(
                HttpClient.newHttpClient(), new ObjectMapper(), "http://127.0.0.1:1", "test-token");
        ExecutionWorkSpec readOnly = new ExecutionWorkSpec("s1", "repair", "kelvinka38/metatron-workforce",
                RepositoryPullRequestAutonomousCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("PR remains unmerged"), List.of("github-pr"));
        var request = new AutonomousExecutionCapability.CapabilityRequest("founder", "organization:metatron", "obj-1",
                readOnly, RepositoryPullRequestAutonomousCapability.WORKER_ID, "assignment-1",
                RepositoryPullRequestAutonomousCapability.AUTHORIZATION_REFERENCE, "dispatch-1", 1);
        assertThrows(SecurityException.class, () -> capability.execute(request));

        ExecutionWorkSpec wrongRepo = new ExecutionWorkSpec("s2", "repair", "kelvinka38/metatron-institution",
                RepositoryPullRequestAutonomousCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("PR remains unmerged"), List.of("github-pr"));
        var wrong = new AutonomousExecutionCapability.CapabilityRequest("founder", "organization:metatron", "obj-2",
                wrongRepo, RepositoryPullRequestAutonomousCapability.WORKER_ID, "assignment-2",
                RepositoryPullRequestAutonomousCapability.AUTHORIZATION_REFERENCE, "dispatch-2", 1);
        assertThrows(SecurityException.class, () -> capability.execute(wrong));
    }
}
