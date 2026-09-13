package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerGovernedAutonomousExecutionCapabilityTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void commanderUsesScheduledWorkBindingWithoutRestaffingCapacityAssignmentOrRuntimeProvisioning() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant-worker-a", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker-a", "participant-worker-a");
        core.participate("participation-worker-a", "worker-a", "org-metatron", "position", "role");
        core.attestCapability("worker-a", HostCommanderAutonomousCapability.CAPABILITY, 1.0, "evidence:commander");
        // Deliberately do not create Availability: broker-governed Work must not replay capacity machinery.

        ExecutionAttemptService attempts = new ExecutionAttemptService();
        AtomicInteger effects = new AtomicInteger();
        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return HostCommanderAutonomousCapability.CAPABILITY; }
            @Override public String authorityReference() { return "authority:commander"; }
            @Override public String authorizationReference() { return "authorization:commander"; }
            @Override public boolean supportsWorker(String workerId) { return "worker-a".equals(workerId); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                assertTrue(request.allocated());
                assertEquals("worker-a", request.allocatedWorkerId());
                assertTrue(request.assignmentReference().startsWith("work-binding:objective-a:commander-step:worker-a"));
                assertTrue(request.executionAttemptFencingToken() > 0);
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "host-commander:objective-a", List.of("broker:verified"), "PASS");
            }
        };

        BrokerGovernedAutonomousExecutionCapability capability =
                new BrokerGovernedAutonomousExecutionCapability(delegate, core, attempts, CLOCK);
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "commander-step", "Verify production operation", "host:metatron-production",
                HostCommanderAutonomousCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        AutonomousExecutionCapability.CapabilityRequest request =
                new AutonomousExecutionCapability.CapabilityRequest("human:primary", "org-metatron", "objective-a", work)
                        .withDispatch("dispatch:objective-a:commander-step", 1)
                        .withSchedulingDecision("schedule:objective-a", "worker-a");

        AutonomousExecutionCapability.CapabilityResult result = capability.execute(request);

        assertTrue(result.success());
        assertEquals(1, effects.get());
        assertTrue(core.allAssignments().isEmpty(), "Commander hot path must not create Core Assignment per tool Work");
        assertTrue(core.allCapacityReservations().isEmpty(), "Commander hot path must not reserve Worker capacity");
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.startsWith("work-execution-binding:worker=worker-a")));
        assertEquals(1, attempts.all().size());
        ExecutionAttempt attempt = attempts.all().getFirst();
        assertEquals(ExecutionAttempt.Status.SUCCEEDED, attempt.status());
        assertEquals("broker:" + HostCommanderAutonomousCapability.CAPABILITY, attempt.runtimeId());
    }
}
