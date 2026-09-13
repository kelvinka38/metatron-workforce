package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostCommanderCapabilityDomainContractTest {
    private final HostCommanderAutonomousCapability capability =
            new HostCommanderAutonomousCapability(new ObjectMapper(), "/tmp/test-key", "test@host");

    @Test
    void commanderAcceptsOnlyOperationsItsBoundedAdapterCanActuallyExpress() {
        ExecutionWorkSpec uptime = new ExecutionWorkSpec(
                "uptime", "Check production host uptime", "Metatron host",
                HostCommanderAutonomousCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("verified uptime"), List.of("Commander verified result"));
        ExecutionWorkSpec repositoryCommit = new ExecutionWorkSpec(
                "commit", "Stage a repository file and create a local Git commit",
                "kelvinka38/metatron-workforce", HostCommanderAutonomousCapability.CAPABILITY,
                List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("local commit exists"), List.of("git status and commit SHA"));
        ExecutionWorkSpec productionProof = new ExecutionWorkSpec(
                "production-proof", "Prove Metatron production can operate safely end-to-end",
                "Metatron production", HostCommanderAutonomousCapability.CAPABILITY,
                List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("production control is demonstrated safely"), List.of("Commander verified result"));

        assertTrue(capability.supportsWork(uptime));
        assertFalse(capability.requiresIndependentObservation(uptime));
        assertTrue(capability.supportsWork(productionProof));
        assertFalse(capability.requiresIndependentObservation(productionProof));
        assertFalse(capability.supportsWork(repositoryCommit));
    }
}
