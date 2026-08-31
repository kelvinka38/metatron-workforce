package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metatron.workforce.management.AutonomyRecoveryProbeCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomyRecoveryProbeObservationVerifierTest {
    @TempDir Path temp;

    @Test
    void intentionallyInsufficientEvidenceBecomesPassAfterSettleWindow() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String objectiveId = "objective-observation-retry";
        String target = "p10-recovery://observation-retry/test-1";
        Instant completedAt = Instant.parse("2026-08-31T13:00:00Z");
        Path marker = AutonomyRecoveryProbeCapability.markerPath(temp, objectiveId, target);
        Files.createDirectories(marker.getParent());
        ObjectNode node = json.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("objectiveId", objectiveId);
        node.put("target", target);
        node.put("mode", "OBSERVATION_RETRY");
        node.put("dispatchReference", "dispatch:1");
        node.put("dispatchAttempt", 1);
        node.put("workerId", AutonomyRecoveryProbeCapability.WORKER_ID);
        node.put("assignmentReference", "assignment:1");
        node.put("authorizationReference", AutonomyRecoveryProbeCapability.AUTHORIZATION_REFERENCE);
        node.put("idempotencyKey", "idempotency:1");
        node.put("completedAt", completedAt.toString());
        Files.writeString(marker, json.writeValueAsString(node));

        ObservationRequirement requirement = new ObservationRequirement(
                "requirement-1", objectiveId, "step-1", "criterion-1", target,
                "recovery probe is independently verified",
                List.of("durable marker"), completedAt.minusSeconds(1));
        AutonomyRecoveryProbeObservationVerifier verifier =
                new AutonomyRecoveryProbeObservationVerifier(json, temp);

        ObservationReport early = verifier.observe(
                requirement, List.of("execution:evidence"), completedAt.plusSeconds(1)).orElseThrow();
        assertEquals(ObservationReport.CriterionResult.INCONCLUSIVE, early.criterionResult());
        assertEquals(ObservationReport.Quality.INSUFFICIENT, early.quality());

        ObservationReport settled = verifier.observe(
                requirement, List.of("execution:evidence"), completedAt.plusSeconds(6)).orElseThrow();
        assertEquals(ObservationReport.CriterionResult.PASS, settled.criterionResult());
        assertEquals(ObservationReport.Quality.HIGH, settled.quality());
        assertTrue(settled.evidenceReferences().stream()
                .anyMatch(value -> value.contains("fresh-durable-marker-read")));
    }
}
