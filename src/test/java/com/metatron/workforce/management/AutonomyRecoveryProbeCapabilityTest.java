package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomyRecoveryProbeCapabilityTest {
    @TempDir Path temp;

    @Test
    void transientTimeoutFailsFirstDispatchThenPersistsSecondAttemptEvidence() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AutonomyRecoveryProbeCapability capability = new AutonomyRecoveryProbeCapability(
                json, temp, Duration.ofMillis(1));
        ExecutionWorkSpec spec = new ExecutionWorkSpec(
                "recover-step", "inject one transient timeout then recover",
                "p10-recovery://transient-timeout/test-1",
                AutonomyRecoveryProbeCapability.CAPABILITY,
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("probe eventually completes after a bounded retry"),
                List.of("durable recovery marker and dispatch evidence"));
        AutonomousExecutionCapability.CapabilityRequest base =
                new AutonomousExecutionCapability.CapabilityRequest("founder", "organization:metatron", "objective-probe", spec)
                        .withAllocation(
                                AutonomyRecoveryProbeCapability.WORKER_ID,
                                "assignment:probe",
                                AutonomyRecoveryProbeCapability.AUTHORIZATION_REFERENCE);

        IllegalStateException first = assertThrows(IllegalStateException.class,
                () -> capability.execute(base.withDispatch("dispatch:1", 1)));
        assertTrue(first.getMessage().contains("provider-timeout-injected"));

        AutonomousExecutionCapability.CapabilityResult result = capability.execute(
                base.withDispatch("dispatch:2", 2));
        assertTrue(result.success());
        assertTrue(result.evidenceReferences().contains("p10-recovery-dispatch-attempt:2"));

        Path marker = AutonomyRecoveryProbeCapability.markerPath(
                temp, "objective-probe", spec.target());
        assertTrue(Files.isRegularFile(marker));
        JsonNode persisted = json.readTree(Files.readString(marker));
        assertEquals("objective-probe", persisted.path("objectiveId").asText());
        assertEquals(2, persisted.path("dispatchAttempt").asInt());
        assertEquals("TRANSIENT_TIMEOUT", persisted.path("mode").asText());
    }

    @Test
    void mutatingWorkIsRejectedBeforeEffect() {
        AutonomyRecoveryProbeCapability capability = new AutonomyRecoveryProbeCapability(
                new ObjectMapper(), temp, Duration.ofMillis(1));
        ExecutionWorkSpec spec = new ExecutionWorkSpec(
                "bad-step", "must not mutate", "p10-recovery://transient-timeout/test-2",
                AutonomyRecoveryProbeCapability.CAPABILITY, List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("no mutation"), List.of("rejection evidence"));
        AutonomousExecutionCapability.CapabilityRequest request =
                new AutonomousExecutionCapability.CapabilityRequest("founder", "organization:metatron", "objective-bad", spec)
                        .withAllocation(
                                AutonomyRecoveryProbeCapability.WORKER_ID,
                                "assignment:probe",
                                AutonomyRecoveryProbeCapability.AUTHORIZATION_REFERENCE)
                        .withDispatch("dispatch:bad", 1);

        assertThrows(SecurityException.class, () -> capability.execute(request));
    }
}
