package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Governance-boundary and bounded-parameter coverage for the least-privilege Housekeeping Worker.
 * Does not exercise the real SSH broker transport (no dedicated coverage exists for
 * HostCommanderAutonomousCapability's identical transport either); these tests specifically prove
 * the capability can never be reached without correct governed allocation, and that any
 * objective-supplied override is clamped to a bounded range before it could ever reach the broker.
 */
class WorkerHousekeepingCapabilityTest {
    private static final WorkerHousekeepingCapability CAPABILITY =
            new WorkerHousekeepingCapability(new ObjectMapper(), "/tmp/unused-test-key", "unused@test-target");

    @Test
    void ungovernedRequestIsRejectedBeforeAnyBrokerCall() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "clean-up-disk", "run housekeeping", "host", WorkerHousekeepingCapability.CAPABILITY,
                List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        AutonomousExecutionCapability.CapabilityRequest unallocated =
                new AutonomousExecutionCapability.CapabilityRequest("human:primary", "org-metatron", "OBJ-1", step);

        assertThrows(SecurityException.class, () -> CAPABILITY.execute(unallocated));
    }

    @Test
    void requestAllocatedToADifferentWorkerIsRejected() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "clean-up-disk", "run housekeeping", "host", WorkerHousekeepingCapability.CAPABILITY,
                List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        AutonomousExecutionCapability.CapabilityRequest wrongWorker =
                new AutonomousExecutionCapability.CapabilityRequest("human:primary", "org-metatron", "OBJ-1", step)
                        .withAllocation("WORKER-GENERAL-ENGINEERING", "assignment:1", "authorization:other")
                        .withDispatch("dispatch:1", 1);

        assertThrows(SecurityException.class, () -> CAPABILITY.execute(wrongWorker));
    }

    @Test
    void requestForADifferentCapabilityIsRejected() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "clean-up-disk", "run housekeeping", "host", "host.commander.execute",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        AutonomousExecutionCapability.CapabilityRequest wrongCapability =
                new AutonomousExecutionCapability.CapabilityRequest("human:primary", "org-metatron", "OBJ-1", step)
                        .withAllocation(WorkerHousekeepingCapability.WORKER_ID, "assignment:1",
                                WorkerHousekeepingCapability.AUTHORIZATION_REFERENCE)
                        .withDispatch("dispatch:1", 1);

        assertThrows(SecurityException.class, () -> CAPABILITY.execute(wrongCapability));
    }

    @Test
    void supportsWorkerIsTrueOnlyForTheHousekeepingWorker() {
        assertEquals(true, CAPABILITY.supportsWorker(WorkerHousekeepingCapability.WORKER_ID));
        assertEquals(false, CAPABILITY.supportsWorker("WORKER-GENERAL-ENGINEERING"));
        assertEquals(false, CAPABILITY.supportsWorker("WORKER-HOUSEKEEPING-IMPOSTOR"));
    }

    @Test
    void boundedIntClampsAnObjectiveSuppliedOverrideIntoTheSafeRange() throws Exception {
        // Package-private static helper exercised via reflection: proves an adversarial or malformed
        // objective text (e.g. "journal 999999mb") can never widen the blast radius past the bound,
        // and that a missing/unparsable value falls back to the documented safe default.
        var method = WorkerHousekeepingCapability.class.getDeclaredMethod(
                "boundedInt", String.class, String.class, int.class, int.class, int.class);
        method.setAccessible(true);

        assertEquals(500, method.invoke(null, "run housekeeping now", "journal", 500, 100, 2000));
        assertEquals(2000, method.invoke(null, "journal 999999mb please", "journal", 500, 100, 2000));
        assertEquals(100, method.invoke(null, "journal 0mb", "journal", 500, 100, 2000));
        assertEquals(48, method.invoke(null, "builder 48h retention", "builder", 24, 6, 168));
    }
}
