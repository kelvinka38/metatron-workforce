package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedAutonomousExecutionDispatchRetryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T15:30:00Z"), ZoneOffset.UTC);

    @Test
    void boundedDispatchRetryGetsFreshAllocationWhileEffectIdempotencyRemainsStable() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant-a", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker-a", "participant-a");
        core.participate("participation-a", "worker-a", "org-metatron", "position", "role");
        core.attestCapability("worker-a", "repository.audit.read", 1.0, "evidence:capability");
        core.setAvailability("worker-a", true, 1.0);

        AtomicInteger effects = new AtomicInteger();
        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "repository.audit.read"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public boolean supportsWorker(String workerId) { return "worker-a".equals(workerId); }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                assertTrue(request.allocated());
                assertEquals("objective-a:work-step:step-a", request.idempotencyKey());
                if (effects.incrementAndGet() == 1) throw new IllegalStateException("transient-provider-failure");
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work-a", List.of("evidence:work-a"), "PASS");
            }
        };

        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO);

        assertThrows(IllegalStateException.class, () -> governed.execute(request(1)));
        var second = governed.execute(request(2));

        assertTrue(second.success());
        assertEquals(2, effects.get());
        assertEquals(2, core.allAssignments().size());
        assertEquals(2, core.allCapacityReservations().size());
        assertEquals(WorkforceCoreService.AssignmentStatus.CANCELLED, core.allAssignments().get(0).status());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, core.allAssignments().get(1).status());
        assertTrue(core.allCapacityReservations().stream()
                .allMatch(r -> r.status() == WorkforceCoreService.ReservationStatus.RELEASED));
        assertEquals(1.0, core.remainingCapacity("worker-a"), 0.000001);
    }

    private static AutonomousExecutionCapability.CapabilityRequest request(int dispatchAttempt) {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-a", "Audit repository", "kelvinka38/metatron-workforce", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);
        return new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", "objective-a", step)
                .withDispatch("objective-a:graph:1:step:step-a:attempt:" + dispatchAttempt, dispatchAttempt);
    }
}
